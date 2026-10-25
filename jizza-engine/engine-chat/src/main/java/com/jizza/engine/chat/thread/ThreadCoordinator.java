package com.jizza.engine.chat.thread;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.chat.ChatScope;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.llm.CostAccountant;
import com.jizza.engine.api.llm.LlmClient;
import com.jizza.engine.api.llm.LlmResponse;
import com.jizza.engine.api.llm.Msg;
import com.jizza.engine.api.orchestrator.Orchestrator;
import com.jizza.engine.api.orchestrator.UiAction;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.util.Redaction;
import com.jizza.engine.chat.config.ChatProperties;
import com.jizza.engine.persistence.entity.AgentEntity;
import com.jizza.engine.persistence.entity.ConversationEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Jizza as the coordinator of a conversation's threads. Before anyone answers a message the user
 * typed in a chat with threads on (or in one of its threads), Jizza decides where it belongs:
 *
 * <ul>
 *   <li>the thread it is already in, or another of this conversation's threads whose purpose it
 *       serves, even an idle or resolved one (reopened for it);
 *   <li>a new thread, when it starts a purpose no thread has. A purpose is whose it is and what it
 *       is for: investing for the user and investing for their sister are two threads, both led by
 *       the finance specialist;
 *   <li>the main chat, for small talk, questions about the app, and requests that span several
 *       specialists' areas.
 * </ul>
 *
 * <p>The decision is one small model call with the conversation's threads in front of it, so it
 * does not rest on the answering agent remembering to call a tool. When the call fails or its
 * answer does not hold up, nothing moves and the turn runs as before, where the thread tools are
 * still offered as a fallback.
 */
@Component
public class ThreadCoordinator {

  private static final Logger log = LoggerFactory.getLogger(ThreadCoordinator.class);

  /** Earlier messages shown with the new one, so "this" and "him" can be resolved. */
  static final int RECENT_MESSAGES = 6;
  private static final int MAX_RECENT_CHARS = 600;

  static final String PROMPT = """
      Your name is Jizza Router. You decide where one message in a conversation belongs. The
      conversation has a main chat, where Jizza coordinates, and threads. Each thread has one
      purpose: whose matter it is (the user, or a named other person) and what it is for. A
      specialist leads it.

      Decide:
      - "thread": the message serves the purpose of one of the listed threads, even if that
        thread is idle or resolved. Follow-ups, answers to the lead's questions, and "what have we
        done about this?" go to the thread the earlier exchange was in. Give its id.
      - "new": the message starts a purpose no listed thread has and belongs to one specialist's
        area. Same specialist but a different person or a different goal is a new purpose: the
        user's own investment plan and an investment plan for their father are two threads. Give
        the lead's key, a title of two to six words naming the purpose (with the person's name when
        it is not the user), and a topic: one or two sentences on whose matter it is and what they
        want.
      - "main": small talk, thanks, questions about the Jizza app itself, a request that spans
        several specialists' areas, or anything you are unsure of.

      Reply with JSON only, no prose:
      {"route": "thread" | "new" | "main", "thread_id": "...", "lead": "...", "title": "...",
       "topic": "...", "reason": "a few words"}""";

  /** Where a message goes. {@code thread} is set for THREAD, {@code lead}/title/topic for NEW. */
  public record Decision(Route route, ConversationEntity thread, AgentEntity lead, String title,
                         String topic, String reason) {
    public static Decision stay() { return new Decision(Route.STAY, null, null, null, null, null); }
  }

  public enum Route { STAY, MAIN, THREAD, NEW }

  private final ThreadService threads;
  private final LlmClient llm;
  private final CostAccountant costs;
  private final ChatProperties props;
  private final AuditLog audit;

  public ThreadCoordinator(ThreadService threads, LlmClient llm, CostAccountant costs,
                           ChatProperties props, AuditLog audit) {
    this.threads = threads;
    this.llm = llm;
    this.costs = costs;
    this.props = props;
    this.audit = audit;
  }

  /**
   * Moves the user's message where it belongs and returns the short reply that says so, or empty
   * when it belongs where it was typed and should be answered there as usual.
   */
  public Optional<Orchestrator.Turn> coordinate(ChatScope.Scope scope, String message, List<Msg> history) {
    if (!applies(scope) || message == null || message.isBlank()) return Optional.empty();
    Decision decision;
    try {
      decision = decide(scope, message, history);
    } catch (RuntimeException e) {
      log.warn("Could not route a message in conversation {}", scope.conversationId(), e);
      return Optional.empty();
    }
    return move(scope, message, decision);
  }

  boolean applies(ChatScope.Scope scope) {
    ChatProperties.Threads config = props.getThreads();
    if (!config.isEnabled() || !config.getRouter().isEnabled() || !costs.withinBudget(scope.userId())) {
      return false;
    }
    return scope.thread() ? scope.parentId() != null : scope.threadsOn();
  }

  Decision decide(ChatScope.Scope scope, String message, List<Msg> history) {
    UUID mainId = scope.thread() ? scope.parentId() : scope.conversationId();
    List<ConversationEntity> mine = threads.threadsOf(scope.userId(), mainId);
    List<AgentEntity> leads = threads.leads(scope.userId());
    if (leads.isEmpty() && mine.isEmpty()) return Decision.stay();

    String model = llm.modelFor(props.getThreads().getRouter().getTier());
    LlmResponse response = llm.complete(model,
        List.of(Msg.system(PROMPT), Msg.user(prompt(scope, message, history, mine, leads))), List.of());
    costs.record(scope.userId(), response.model() == null ? model : response.model(),
        response.inputTokens(), response.outputTokens(), "chat:thread-route");
    return parse(response.text(), scope, mine, leads);
  }

  private String prompt(ChatScope.Scope scope, String message, List<Msg> history,
                        List<ConversationEntity> mine, List<AgentEntity> leads) {
    ObjectNode body = Json.obj();
    body.put("typed_in", scope.thread()
        ? "the thread " + scope.conversationId() + " (\"" + scope.title() + "\")"
        : "the main chat");
    ArrayNode listed = body.putArray("threads");
    for (ConversationEntity t : mine) {
      ObjectNode node = listed.addObject();
      node.put("id", t.getId().toString());
      node.put("title", t.getTitle());
      node.put("purpose", t.getTopic());
      node.put("lead", t.getOwnerAgentId() == null ? "jizza"
          : threads.agent(t.getOwnerAgentId()).map(AgentEntity::getKey).orElse("jizza"));
      node.put("state", t.getArchivedAt() != null ? "resolved" : "open");
    }
    ArrayNode specialists = body.putArray("specialists");
    for (AgentEntity a : leads) {
      ObjectNode node = specialists.addObject();
      node.put("key", a.getKey());
      node.put("name", a.getName());
      if (a.getDescription() != null) node.put("area", a.getDescription());
    }
    ArrayNode recent = body.putArray("recent_messages_here");
    List<Msg> said = history.stream()
        .filter(m -> (m.role() == Msg.Role.USER || m.role() == Msg.Role.ASSISTANT)
            && m.text() != null && !m.text().isBlank() && m.toolCalls().isEmpty())
        .toList();
    for (Msg m : said.subList(Math.max(0, said.size() - RECENT_MESSAGES), said.size())) {
      recent.addObject().put("from", m.role() == Msg.Role.USER ? "user" : "assistant")
          .put("text", clip(Redaction.scrub(m.text())));
    }
    body.put("message", Redaction.scrub(message));
    return Json.write(body);
  }

  /** The model's answer, checked against what exists; anything off means it stays put. */
  Decision parse(String raw, ChatScope.Scope scope, List<ConversationEntity> mine, List<AgentEntity> leads) {
    JsonNode node = json(raw);
    if (node == null) return Decision.stay();
    String route = node.path("route").asText("");
    String reason = ThreadService.clip(node.path("reason").asText(""), 200);
    switch (route) {
      case "main" -> {
        return scope.thread() ? new Decision(Route.MAIN, null, null, null, null, reason) : Decision.stay();
      }
      case "thread" -> {
        String id = node.path("thread_id").asText("");
        ConversationEntity target = mine.stream().filter(t -> t.getId().toString().equals(id))
            .findFirst().orElse(null);
        if (target == null) return Decision.stay();
        if (target.getId().equals(scope.conversationId())) return Decision.stay();
        return new Decision(Route.THREAD, target, null, null, null, reason);
      }
      case "new" -> {
        String key = node.path("lead").asText("").strip();
        AgentEntity lead = "jizza".equalsIgnoreCase(key) ? null : leads.stream()
            .filter(a -> a.getKey().equalsIgnoreCase(key) || a.getName().equalsIgnoreCase(key))
            .findFirst().orElse(null);
        if (lead == null && !"jizza".equalsIgnoreCase(key)) return Decision.stay();
        String title = ThreadService.clip(node.path("title").asText(""), ThreadService.MAX_TITLE);
        if (title.isEmpty()) return Decision.stay();
        return new Decision(Route.NEW, null, lead, title,
            ThreadService.clip(node.path("topic").asText(""), ThreadService.MAX_TOPIC), reason);
      }
      default -> {
        return Decision.stay();
      }
    }
  }

  private Optional<Orchestrator.Turn> move(ChatScope.Scope scope, String message, Decision decision) {
    if (decision.route() == Route.STAY) return Optional.empty();
    UUID userId = scope.userId();
    String text;
    UiAction action;
    if (decision.route() == Route.MAIN) {
      String leadName = threads.agent(scope.responderAgentId()).map(AgentEntity::getName).orElse("Jizza");
      threads.handBack(scope, leadName, message, decision.reason());
      text = "That isn't about \"" + scope.title() + "\", so Jizza is picking it up in the main chat.";
      action = new UiAction(UiAction.THREAD_HANDBACK, Json.obj()
          .put("conversationId", scope.parentId().toString())
          .put("threadId", scope.conversationId().toString())
          .put("reason", decision.reason()));
    } else {
      ThreadService.Moved moved = decision.route() == Route.THREAD
          ? threads.continueThread(userId, decision.thread(), message)
          : threads.startThread(userId, scope.conversationId(), decision.lead(), decision.title(),
              decision.topic(), message);
      String title = moved.thread().getTitle();
      text = scope.thread()
          ? "That belongs to another thread, so Jizza moved it to \"" + title + "\", where "
              + moved.leadName() + " is answering it."
          : moved.resumed()
              ? moved.leadName() + " is picking this up in the thread \"" + title + "\"."
              : "I've opened a thread with " + moved.leadName() + " for this: \"" + title + "\".";
      action = new UiAction(UiAction.THREAD_OPENED, ThreadService.card(moved));
    }
    audit.record(userId, ActorType.AGENT, scope.responderAgentId(), "chat.thread.route",
        scope.conversationId().toString(), Json.obj().put("route", decision.route().name().toLowerCase()));
    return Optional.of(new Orchestrator.Turn(text, List.of(), List.of(action), 0, 0));
  }

  private static JsonNode json(String raw) {
    if (raw == null) return null;
    int start = raw.indexOf('{');
    int end = raw.lastIndexOf('}');
    if (start < 0 || end <= start) return null;
    try {
      return Json.read(raw.substring(start, end + 1));
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static String clip(String s) {
    return s.length() <= MAX_RECENT_CHARS ? s : s.substring(0, MAX_RECENT_CHARS) + "…";
  }
}
