package com.jizza.engine.chat.thread;

import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.chat.ChatScope;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.error.ConflictException;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.orchestrator.ConversationStore;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.chat.config.ChatProperties;
import com.jizza.engine.chat.turn.TurnRunner;
import com.jizza.engine.chat.turn.TurnStore;
import com.jizza.engine.persistence.entity.AgentEntity;
import com.jizza.engine.persistence.entity.ConversationEntity;
import com.jizza.engine.persistence.repository.AgentRepository;
import com.jizza.engine.persistence.repository.ConversationRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Threads: the main chat is where Jizza coordinates, and each purpose (one goal, for one person)
 * moves into a thread of its own, led by the specialist whose area it is. Jizza co-owns every
 * thread; a message in a thread that is about something else goes back to the main chat, where
 * Jizza opens the right thread for it. A thread belongs to the chat it was opened from and is never
 * reached from another. Each thread keeps a guide (see {@link ThreadGuides}) and its own memory.
 */
@Service
public class ThreadService {

  private static final Logger log = LoggerFactory.getLogger(ThreadService.class);

  /** How long a moved message waits for a reply already running where it is going. */
  private static final Duration DELIVERY_WAIT = Duration.ofMinutes(5);

  static final int MAX_TITLE = 80;
  static final int MAX_TOPIC = 400;

  /** Threads named to Jizza in a main chat's preamble, at most. */
  static final int MAX_ROSTER = 20;

  private final ConversationRepository conversations;
  private final AgentRepository agents;
  private final TurnStore store;
  private final ObjectProvider<TurnRunner> turns;
  private final AuditLog audit;
  private final ChatProperties props;
  private final ConversationStore summaries;
  private final ExecutorService delivery =
      Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("jizza-thread-", 0).factory());

  public ThreadService(ConversationRepository conversations, AgentRepository agents, TurnStore store,
                       ObjectProvider<TurnRunner> turns, AuditLog audit, ChatProperties props,
                       ConversationStore summaries) {
    this.conversations = conversations;
    this.agents = agents;
    this.store = store;
    this.turns = turns;
    this.audit = audit;
    this.props = props;
    this.summaries = summaries;
  }

  /**
   * How a turn in a conversation is set up.
   *
   * @param lead      the specialist answering, or null when Jizza does
   * @param preamble  what the answering agent is told about the thread; null in the main chat
   */
  public record Framing(ChatScope.Scope scope, AgentEntity lead, String preamble) {
    public boolean thread() { return scope.thread(); }
  }

  /** Null when the conversation is gone. */
  @Transactional(readOnly = true)
  public Framing frame(UUID userId, UUID conversationId) {
    ConversationEntity c = conversations.findById(conversationId).orElse(null);
    if (c == null || !c.getUserId().equals(userId)) return null;
    UUID jizzaId = agents.findByUserIdAndIsOrchestratorTrue(userId).map(AgentEntity::getId).orElse(null);
    if (!c.isThread()) {
      boolean on = c.isThreadsEnabled() && props.getThreads().isEnabled();
      return new Framing(ChatScope.Scope.main(userId, c.getId(), jizzaId, on), null,
          on ? ThreadGuides.mainPreamble(roster(userId, c.getId())) : null);
    }

    AgentEntity owner = c.getOwnerAgentId() == null ? null
        : agents.findById(c.getOwnerAgentId()).orElse(null);
    AgentEntity lead = owner != null && available(owner) ? owner : null;
    boolean standIn = owner != null && lead == null;
    UUID leadId = lead != null ? lead.getId() : jizzaId;
    String leadName = owner != null ? owner.getName() : "Jizza";
    ChatScope.Scope scope = ChatScope.Scope.thread(userId, c.getId(), c.getParentId(), leadId,
        c.getTitle(), c.getTopic());
    return new Framing(scope, lead,
        ThreadGuides.preamble(c.getTitle(), c.getTopic(), leadName, lead == null, standIn, c.getGuide()));
  }

  /** Enabled, active specialists of this user that can lead a thread. */
  @Transactional(readOnly = true)
  public List<AgentEntity> leads(UUID userId) {
    return agents.findByUserIdAndIsOrchestratorFalseAndEnabledTrue(userId).stream()
        .filter(ThreadService::available)
        .toList();
  }

  /** A main chat's threads, resolved ones included, most recently active first. */
  @Transactional(readOnly = true)
  public List<ConversationEntity> threadsOf(UUID userId, UUID mainId) {
    return conversations.findByParentIdOrderByLastMessageAtDesc(mainId).stream()
        .filter(t -> t.getUserId().equals(userId) && t.isThread())
        .toList();
  }

  /** Where each of a main chat's threads stands, as Jizza is told it when coordinating there. */
  List<ThreadGuides.Line> roster(UUID userId, UUID mainId) {
    java.util.Map<UUID, String> guides = new java.util.HashMap<>();
    threadsOf(userId, mainId).forEach(t -> guides.put(t.getId(), t.getGuide()));
    return summaries.threads(userId, mainId).stream()
        .limit(MAX_ROSTER)
        .map(t -> new ThreadGuides.Line(t.id().toString(), t.title(),
            t.lead() == null ? "Jizza" : t.lead().name(), t.stage(), t.topic(),
            ThreadGuides.digest(guides.get(t.id()), 240)))
        .toList();
  }

  /** A resolved thread the user's message carries on: open again, and say so in it. */
  @Transactional
  public void reopen(UUID userId, UUID threadId) {
    ConversationEntity thread = owned(userId, threadId);
    if (thread.getArchivedAt() == null) return;
    thread.setArchivedAt(null);
    store.note(threadId, "Jizza reopened this thread for a follow-up.",
        Json.obj().put("kind", "thread_reopened"));
  }

  @Transactional(readOnly = true)
  public Optional<AgentEntity> agent(UUID agentId) {
    return agentId == null ? Optional.empty() : agents.findById(agentId);
  }

  /**
   * Opens a thread from {@code fromConversationId} (a main chat; a thread's own main chat when it
   * is one), led by {@code lead}, or by Jizza when null.
   */
  @Transactional
  public ConversationEntity open(UUID userId, UUID fromConversationId, AgentEntity lead, String title,
                                 String topic) {
    if (!props.getThreads().isEnabled()) throw new ValidationException("Threads are turned off");
    ConversationEntity from = owned(userId, fromConversationId);
    UUID mainId = from.isThread() ? from.getParentId() : from.getId();
    if (lead != null && (!lead.getUserId().equals(userId) || lead.isOrchestrator() || !available(lead))) {
      throw new ValidationException("That agent can't lead a thread");
    }
    String cleanTitle = clip(title, MAX_TITLE);
    if (cleanTitle.isEmpty()) throw new ValidationException("A thread needs a title");
    String cleanTopic = clip(topic, MAX_TOPIC);
    String leadName = lead == null ? "Jizza" : lead.getName();
    Instant now = Instant.now();

    ConversationEntity thread = new ConversationEntity();
    thread.setUserId(userId);
    thread.setKind(ConversationEntity.KIND_THREAD);
    thread.setParentId(mainId);
    thread.setOwnerAgentId(lead == null ? null : lead.getId());
    thread.setTitle(cleanTitle);
    // Named on purpose, so the first exchange does not rename it.
    thread.setTitleSource("model");
    thread.setTopic(cleanTopic.isEmpty() ? cleanTitle : cleanTopic);
    thread.setGuide(ThreadGuides.initial(cleanTitle, thread.getTopic(), leadName, now));
    thread.setGuideSource("jizza");
    thread.setGuideUpdatedAt(now);
    thread.setLastMessageAt(now);
    thread = conversations.saveAndFlush(thread);

    store.note(thread.getId(), lead == null
            ? "Jizza opened this thread from your main chat."
            : "Jizza opened this thread from your main chat and handed it to " + leadName
                + ". Anything off this topic goes back to the main chat.",
        Json.obj().put("kind", "thread_opened").put("parentId", mainId.toString()));
    audit.record(userId, ActorType.AGENT, null, "chat.thread.open", thread.getId().toString(),
        Json.obj().put("parentId", mainId.toString())
            .put("lead", lead == null ? "jizza" : lead.getKey()));
    return thread;
  }

  /**
   * Adds a note to a section of a thread's guide, at once, for a lead that just learned something
   * worth keeping.
   */
  @Transactional
  public String note(UUID userId, UUID threadId, String section, String note) {
    ConversationEntity thread = owned(userId, threadId);
    if (!thread.isThread()) throw new ValidationException("Only a thread has a guide");
    String clean = clip(note, 300);
    if (clean.isEmpty()) throw new ValidationException("The note is empty");
    String updated = ThreadGuides.append(thread.getGuide(), section, clean);
    if (updated.length() > props.getThreads().getMaxGuideChars() * 2) {
      throw new ValidationException("The guide is full; the next update will condense it");
    }
    thread.setGuide(updated);
    thread.setGuideUpdatedAt(Instant.now());
    return updated;
  }

  /**
   * The user's message, moved into {@code conversationId} and answered there. Runs on its own,
   * waiting its turn if a reply is already running there.
   *
   * @param text   what the user wrote, shown and kept as their message
   * @param input  what the answering agent is told, which says where the message came from
   * @param origin {@code handoff} into a thread, {@code handback} to the main chat
   */
  public void deliver(UUID userId, UUID conversationId, String text, String input, String origin) {
    delivery.execute(() -> {
      Instant giveUp = Instant.now().plus(DELIVERY_WAIT);
      while (true) {
        try {
          turns.getObject().handOff(userId, conversationId, text, input, origin);
          return;
        } catch (ConflictException busy) {
          if (Instant.now().isAfter(giveUp)) {
            log.warn("Gave up moving a message into conversation {}: it stayed busy", conversationId);
            return;
          }
          try {
            Thread.sleep(500);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
          }
        } catch (RuntimeException e) {
          log.warn("Could not move a message into conversation {}", conversationId, e);
          return;
        }
      }
    });
  }

  /** A line in a conversation recording that a message moved. */
  public void noteIn(UUID conversationId, String text, com.fasterxml.jackson.databind.JsonNode data) {
    store.note(conversationId, text, data);
  }

  private ConversationEntity owned(UUID userId, UUID conversationId) {
    ConversationEntity c = conversations.findById(conversationId)
        .orElseThrow(() -> new NotFoundException("Conversation", conversationId));
    if (!c.getUserId().equals(userId)) throw new NotFoundException("Conversation", conversationId);
    return c;
  }

  static boolean available(AgentEntity agent) {
    return agent.isEnabled() && "active".equals(agent.getStatus());
  }

  static String clip(String s, int max) {
    if (s == null) return "";
    String one = s.replaceAll("\\s+", " ").strip();
    return one.length() <= max ? one : one.substring(0, max - 1).strip() + "…";
  }

  @jakarta.annotation.PreDestroy
  void shutdown() {
    delivery.shutdown();
  }
}
