package com.jizza.engine.chat.thread;

import com.jizza.engine.api.llm.CostAccountant;
import com.jizza.engine.api.llm.LlmClient;
import com.jizza.engine.api.llm.LlmResponse;
import com.jizza.engine.api.llm.Msg;
import com.jizza.engine.api.util.Redaction;
import com.jizza.engine.chat.config.ChatProperties;
import com.jizza.engine.persistence.entity.ConversationEntity;
import com.jizza.engine.persistence.repository.ConversationRepository;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Keeps each thread's guide up to date: after a turn in a thread, a small model folds what the
 * exchange taught into the guide, off the user's path. A guide the user edited is built on, not
 * replaced: the prompt keeps their lines unless the conversation shows they no longer hold.
 */
@Component
public class ThreadGuideKeeper {

  private static final Logger log = LoggerFactory.getLogger(ThreadGuideKeeper.class);
  private static final int MAX_EXCHANGE_CHARS = 3_000;
  static final String NO_CHANGE = "NO_CHANGE";

  private final ConversationRepository conversations;
  private final LlmClient llm;
  private final CostAccountant costs;
  private final ChatProperties props;
  private final Set<UUID> busy = ConcurrentHashMap.newKeySet();
  private final ExecutorService executor =
      Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("jizza-guide-", 0).factory());

  public ThreadGuideKeeper(ConversationRepository conversations, LlmClient llm, CostAccountant costs,
                           ChatProperties props) {
    this.conversations = conversations;
    this.llm = llm;
    this.costs = costs;
    this.props = props;
  }

  /** Called once a thread's turn has finished. Returns at once. */
  public void afterTurn(UUID userId, UUID threadId, String asked, String answered) {
    ChatProperties.Threads threads = props.getThreads();
    if (!threads.isEnabled() || !threads.getGuides().isEnabled()) return;
    if (answered == null || answered.isBlank()) return;
    executor.execute(() -> {
      if (!busy.add(threadId)) return; // the next turn will catch up
      try {
        update(userId, threadId, asked, answered);
      } catch (RuntimeException e) {
        log.warn("Could not update the guide of thread {}", threadId, e);
      } finally {
        busy.remove(threadId);
      }
    });
  }

  void update(UUID userId, UUID threadId, String asked, String answered) {
    ConversationEntity thread = conversations.findById(threadId).orElse(null);
    if (thread == null || !thread.isThread() || !costs.withinBudget(userId)) return;
    int max = props.getThreads().getMaxGuideChars();
    String prompt = "<guide>\n" + (thread.getGuide() == null ? "" : thread.getGuide().strip())
        + "\n</guide>\n<latest_exchange>\nUser: " + clip(asked) + "\nAssistant: " + clip(answered)
        + "\n</latest_exchange>";
    String model = llm.modelFor(props.getThreads().getGuides().getTier());
    LlmResponse response = llm.complete(model,
        List.of(Msg.system(ThreadGuides.KEEPER_PROMPT.formatted(max)), Msg.user(prompt)), List.of());
    costs.record(userId, response.model() == null ? model : response.model(),
        response.inputTokens(), response.outputTokens(), "chat:thread-guide");
    String updated = accepted(response.text(), max);
    if (updated == null) return;

    ConversationEntity fresh = conversations.findById(threadId).orElse(null);
    if (fresh == null) return;
    // Edited by hand or noted meanwhile: what the model saw is stale, so leave it for next time.
    if (fresh.getGuideUpdatedAt() != null && thread.getGuideUpdatedAt() != null
        && fresh.getGuideUpdatedAt().isAfter(thread.getGuideUpdatedAt())) {
      return;
    }
    fresh.setGuide(updated);
    fresh.setGuideUpdatedAt(Instant.now());
    conversations.save(fresh);
  }

  /** The model's guide, tidied; null when there is nothing to change or it is not a guide. */
  static String accepted(String raw, int max) {
    if (raw == null) return null;
    String text = raw.strip();
    if (text.isEmpty() || text.startsWith(NO_CHANGE)) return null;
    // A model that wrapped the file in a code fence.
    if (text.startsWith("```")) {
      text = text.replaceFirst("^```[a-zA-Z]*\\s*", "").replaceFirst("\\s*```$", "").strip();
    }
    if (!text.startsWith("# ")) return null;
    text = Redaction.scrub(text);
    if (text.length() > max) text = text.substring(0, max).replaceFirst("\\n[^\\n]*$", "");
    return text + "\n";
  }

  private static String clip(String s) {
    if (s == null) return "";
    return s.length() <= MAX_EXCHANGE_CHARS ? s : s.substring(0, MAX_EXCHANGE_CHARS) + "…";
  }

  @jakarta.annotation.PreDestroy
  void shutdown() {
    executor.shutdown();
  }
}
