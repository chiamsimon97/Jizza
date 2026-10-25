package com.jizza.engine.chat.thread;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.chat.ChatScope;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.error.JizzaException;
import com.jizza.engine.api.orchestrator.UiAction;
import com.jizza.engine.api.tool.JsonSchemas;
import com.jizza.engine.api.tool.ToolCall;
import com.jizza.engine.api.tool.ToolProvider;
import com.jizza.engine.api.tool.ToolResult;
import com.jizza.engine.api.tool.ToolSpec;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.chat.config.ChatProperties;
import com.jizza.engine.persistence.entity.AgentEntity;
import com.jizza.engine.persistence.entity.ConversationEntity;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The tools that move a conversation between the main chat and its threads. Offered only to the
 * agent answering the user in this turn (see {@link ChatScope}):
 *
 * <ul>
 *   <li>In the main chat, Jizza gets {@code start_thread} and, when the chat has threads,
 *       {@code continue_in_thread} for one of them. Only this chat's threads: a thread opened in
 *       one chat is never continued from another.
 *   <li>In a thread, its lead gets {@code hand_back_to_jizza} for a message off the thread's topic
 *       and {@code note_in_thread_guide} to keep what it learned.
 * </ul>
 */
@Component
public class ThreadToolProvider implements ToolProvider {

  public static final String START = "start_thread";
  public static final String CONTINUE = "continue_in_thread";
  public static final String HAND_BACK = "hand_back_to_jizza";
  public static final String NOTE = "note_in_thread_guide";

  /** Threads named in continue_in_thread's description, at most. */
  static final int MAX_LISTED_THREADS = 12;

  private final ThreadService threads;
  private final ChatProperties props;
  private final AuditLog audit;

  public ThreadToolProvider(ThreadService threads, ChatProperties props, AuditLog audit) {
    this.threads = threads;
    this.props = props;
    this.audit = audit;
  }

  @Override
  public List<ToolSpec> toolsFor(UUID agentId) {
    Optional<ChatScope.Scope> current = ChatScope.current();
    if (current.isEmpty() || !props.getThreads().isEnabled() || !ChatScope.isResponder(agentId)) {
      return List.of();
    }
    ChatScope.Scope scope = current.get();
    if (scope.thread()) return threadTools(scope);
    // A plain chat: the user did not turn threads on for it.
    return scope.threadsOn() ? mainTools(scope) : List.of();
  }

  @Override
  public boolean supports(UUID agentId, String toolName) {
    return toolsFor(agentId).stream().anyMatch(t -> t.name().equals(toolName));
  }

  /** A note may be about the user's health or money: the log records the call, not what it said. */
  @Override
  public boolean redactArguments(String toolName) {
    return true;
  }

  // ── The main chat ──────────────────────────────────────────────────────────────

  private List<ToolSpec> mainTools(ChatScope.Scope scope) {
    List<AgentEntity> leads = threads.leads(scope.userId());
    StringBuilder roster = new StringBuilder();
    for (AgentEntity a : leads) {
      roster.append("\n- ").append(a.getKey()).append(": ").append(a.getName());
      if (a.getDescription() != null && !a.getDescription().isBlank()) {
        roster.append(" -- ").append(a.getDescription());
      }
    }
    roster.append("\n- jizza: you, for a topic that deserves its own thread but is no specialist's area");

    ObjectNode startSchema = JsonSchemas.object(new LinkedHashMap<>(Map.of(
            "lead", JsonSchemas.string("Who leads the thread: one of the keys listed in the description"),
            "title", JsonSchemas.string("Two to six words naming the purpose, in the user's terms; "
                + "name the person when it is not the user (\"Mum's retirement savings\")"),
            "for_whom", JsonSchemas.string("Who the purpose is for: \"me\" for the user, otherwise "
                + "the person's name and how they relate to the user (\"Priya, my sister\")"),
            "topic", JsonSchemas.string("One or two sentences on the one goal this thread serves and "
                + "what the user wants from it. This decides which later messages belong in it."),
            "message", JsonSchemas.string("The user's message (or the part of it about this "
                + "purpose) to hand over, as they wrote it"))),
        // Who it is for stays optional, so a call without it still opens the thread.
        List.of("lead", "title", "topic", "message"));

    List<ToolSpec> tools = new ArrayList<>();
    tools.add(new ToolSpec(START, """
        Open a thread for one purpose -- one goal, for one person -- led by the specialist whose
        area it is, and hand them the user's message to answer there. You coordinate from this
        chat; the lead works with the user in the thread, and you co-own it. The user turned
        threads on for this chat, so this is how their requests get worked on.

        Open one whenever the user brings up a purpose none of this chat's threads serves,
        even a quick question. For example investments, budgets, bills or savings go to the finance lead;
        health, blood tests, vitamins, fitness, sleep, symptoms or appointments go to the health
        lead; meetings, documents or deadlines go to the work lead; anything no specialist covers
        goes to a thread you lead (key "jizza").

        One thread per purpose: the same kind of request for someone else (an investment plan for
        the user's mother when their own plan has a thread) or for a different goal (filing taxes
        when the thread is about monthly investing) gets a new thread, even with the same lead.
        A message about several purposes: call this (or continue_in_thread) once per purpose, each
        with only its part.

        Answer here instead only small talk, questions about the Jizza app itself, and questions
        about how this chat's threads stand. If one of this chat's threads already serves the
        purpose, use continue_in_thread instead.

        After calling it, tell the user in one sentence who took it to which thread. Do not answer
        the request yourself.

        Leads:""" + roster, startSchema, false));

    List<ConversationEntity> ours = threads.threadsOf(scope.userId(), scope.conversationId()).stream()
        .limit(MAX_LISTED_THREADS).toList();
    if (!ours.isEmpty()) {
      StringBuilder listed = new StringBuilder();
      for (ConversationEntity t : ours) {
        String lead = t.getOwnerAgentId() == null ? "Jizza"
            : threads.agent(t.getOwnerAgentId()).map(AgentEntity::getName).orElse("Jizza");
        listed.append("\n- ").append(t.getId()).append(": \"").append(t.getTitle()).append("\", led by ")
            .append(lead).append(t.getArchivedAt() != null ? ", resolved" : "").append(". ")
            .append(t.getTopic() == null ? "" : t.getTopic());
      }
      ObjectNode continueSchema = JsonSchemas.object(new LinkedHashMap<>(Map.of(
              "thread_id", JsonSchemas.string("The id of one of this chat's threads listed in the description"),
              "message", JsonSchemas.string("The user's message (or the part of it for this "
                  + "thread) to hand over, as they wrote it"))),
          List.of("thread_id", "message"));
      tools.add(new ToolSpec(CONTINUE, """
          Hand the user's message to the thread of this chat whose purpose it carries on -- same
          goal, same person -- where its lead answers it with everything the thread already knows
          and remembers. Idle and resolved threads count: a follow-up to something a thread handled
          (such as "what have we done about this?") goes back to it, and a resolved one reopens.
          A similar request for someone else or for a different goal is not a follow-up: open a
          new thread for it. After calling it, tell the user in one sentence where it went; do not
          answer it yourself.

          This chat's threads:""" + listed, continueSchema, false));
    }
    return tools;
  }

  // ── A thread ───────────────────────────────────────────────────────────────────

  private List<ToolSpec> threadTools(ChatScope.Scope scope) {
    ObjectNode backSchema = JsonSchemas.object(new LinkedHashMap<>(Map.of(
            "message", JsonSchemas.string("The user's message, as they wrote it"),
            "reason", JsonSchemas.string("Why it does not belong in this thread, in a few words"))),
        List.of("message", "reason"));
    ObjectNode section = JsonSchemas.string("Where the note goes");
    var options = section.putArray("enum");
    ThreadGuides.NOTE_SECTIONS.forEach(options::add);
    ObjectNode noteSchema = JsonSchemas.object(new LinkedHashMap<>(Map.of(
            "section", section,
            "note", JsonSchemas.string("One short line worth keeping for this thread"))),
        List.of("section", "note"));
    return List.of(
        new ToolSpec(HAND_BACK, """
            Pass the user's message back to Jizza in the main chat because it does not serve this
            thread's purpose ("%s"): it is about something else, or about the same kind of thing
            for another person or a different goal. Jizza answers it there or opens the right
            thread for it, so this thread stays about one thing. Never use it for follow-ups,
            related questions or small talk. After calling it, say in one sentence that Jizza will pick it
            up in the main chat, and do not answer it.""".formatted(scope.title()),
            backSchema, false),
        new ToolSpec(NOTE, """
            Add one line to this thread's guide, the learning file read at the start of every turn
            here: a fact about the user's situation, a decision, a preference, or a question still
            open. Keep it to what matters for this thread's topic; never a credential or account
            number.""", noteSchema, false));
  }

  // ── Running them ───────────────────────────────────────────────────────────────

  @Override
  public ToolResult invoke(UUID agentId, ToolCall call) {
    ChatScope.Scope scope = ChatScope.current().orElse(null);
    if (scope == null) return ToolResult.error("This only works in a conversation with the user");
    JsonNode args = call.args() == null ? Json.obj() : call.args();
    try {
      return switch (call.name()) {
        case START -> start(scope, args);
        case CONTINUE -> resume(scope, args);
        case HAND_BACK -> handBack(scope, args);
        case NOTE -> note(scope, args);
        default -> ToolResult.error("Unknown tool " + call.name());
      };
    } catch (JizzaException e) {
      return ToolResult.error(e.getMessage());
    }
  }

  private ToolResult start(ChatScope.Scope scope, JsonNode args) {
    String key = args.path("lead").asText("").strip();
    String message = args.path("message").asText("").strip();
    if (message.isEmpty()) return ToolResult.error("Say which message to hand over");
    AgentEntity lead = null;
    if (!key.isEmpty() && !"jizza".equalsIgnoreCase(key)) {
      lead = threads.leads(scope.userId()).stream()
          .filter(a -> a.getKey().equalsIgnoreCase(key) || a.getName().equalsIgnoreCase(key))
          .findFirst().orElse(null);
      if (lead == null) return ToolResult.error("No specialist '" + key + "' can lead a thread");
    }
    ConversationEntity thread = threads.open(scope.userId(), scope.conversationId(), lead,
        args.path("title").asText(""), forWhom(args.path("topic").asText(""), args.path("for_whom").asText("")));
    String leadName = lead == null ? "Jizza" : lead.getName();
    threads.deliver(scope.userId(), thread.getId(), message, handoffInput(message, thread.getTopic()),
        "handoff");
    ChatScope.record(new UiAction(UiAction.THREAD_OPENED, threadCard(thread, lead, false)));
    return ToolResult.ok(Json.obj().put("threadId", thread.getId().toString())
        .put("title", thread.getTitle()).put("lead", leadName)
        .put("status", leadName + " is answering the user in the new thread. Tell the user in one "
            + "sentence; do not answer the request yourself."));
  }

  private ToolResult resume(ChatScope.Scope scope, JsonNode args) {
    String message = args.path("message").asText("").strip();
    if (message.isEmpty()) return ToolResult.error("Say which message to hand over");
    UUID id;
    try {
      id = UUID.fromString(args.path("thread_id").asText(""));
    } catch (IllegalArgumentException e) {
      return ToolResult.error("Use the id of one of the open threads");
    }
    // Only this chat's own threads: one opened in another chat is out of reach.
    ConversationEntity thread = threads.threadsOf(scope.userId(), scope.conversationId()).stream()
        .filter(t -> t.getId().equals(id)).findFirst().orElse(null);
    if (thread == null) return ToolResult.error("That is not one of this chat's threads");
    threads.reopen(scope.userId(), thread.getId());
    AgentEntity lead = threads.agent(thread.getOwnerAgentId()).orElse(null);
    threads.deliver(scope.userId(), thread.getId(), message, handoffInput(message, thread.getTopic()),
        "handoff");
    ChatScope.record(new UiAction(UiAction.THREAD_OPENED, threadCard(thread, lead, true)));
    String leadName = lead == null ? "Jizza" : lead.getName();
    return ToolResult.ok(Json.obj().put("threadId", thread.getId().toString())
        .put("title", thread.getTitle()).put("lead", leadName)
        .put("status", leadName + " is answering it in that thread. Tell the user in one sentence."));
  }

  private ToolResult handBack(ChatScope.Scope scope, JsonNode args) {
    String message = args.path("message").asText("").strip();
    String reason = ThreadService.clip(args.path("reason").asText(""), 200);
    if (message.isEmpty()) return ToolResult.error("Say which message to pass back");
    if (scope.parentId() == null) return ToolResult.error("This thread has no main chat to go back to");
    String leadName = ChatScope.current().flatMap(s -> threads.agent(s.responderAgentId()))
        .map(AgentEntity::getName).orElse("The thread's lead");
    threads.noteIn(scope.parentId(), leadName + " passed this back from the thread \"" + scope.title() + "\".",
        Json.obj().put("kind", "thread_handback").put("threadId", scope.conversationId().toString()));
    String input = """
        [This message comes from the thread "%s" (led by %s, about: %s), where it was off topic. \
        %s said: %s. Handle it as a new message in the main chat: answer it here, open a thread \
        for it with start_thread, or hand it to an open thread that fits with continue_in_thread.]

        %s""".formatted(scope.title(), leadName, scope.topic() == null ? scope.title() : scope.topic(),
        leadName, reason.isEmpty() ? "it is about something else" : reason, message);
    threads.deliver(scope.userId(), scope.parentId(), message, input, "handback");
    audit.record(scope.userId(), ActorType.AGENT, scope.responderAgentId(), "chat.thread.handback",
        scope.conversationId().toString(), Json.obj().put("parentId", scope.parentId().toString()));
    ChatScope.record(new UiAction(UiAction.THREAD_HANDBACK, Json.obj()
        .put("conversationId", scope.parentId().toString())
        .put("threadId", scope.conversationId().toString())
        .put("reason", reason)));
    return ToolResult.ok("Passed to Jizza in the main chat. Tell the user in one sentence that "
        + "Jizza will pick it up there; do not answer it here.");
  }

  private ToolResult note(ChatScope.Scope scope, JsonNode args) {
    String section = args.path("section").asText("");
    String chosen = ThreadGuides.NOTE_SECTIONS.stream().filter(s -> s.equalsIgnoreCase(section))
        .findFirst().orElse(ThreadGuides.KNOWN);
    threads.note(scope.userId(), scope.conversationId(), chosen, args.path("note").asText(""));
    return ToolResult.ok("Kept in the thread's guide under \"" + chosen + "\".");
  }

  /** A thread's topic, saying who it is for when that is not the user themselves. */
  static String forWhom(String topic, String whom) {
    String who = whom == null ? "" : whom.strip();
    String what = topic == null ? "" : topic.strip();
    if (who.isEmpty() || who.equalsIgnoreCase("me") || who.equalsIgnoreCase("myself")
        || who.equalsIgnoreCase("the user") || who.equalsIgnoreCase("user")) {
      return what;
    }
    return "For " + who + ": " + what;
  }

  /** What the lead is told with a message Jizza handed over from the main chat. */
  static String handoffInput(String message, String topic) {
    return "[Jizza handed this message over from the main chat, because it belongs in this thread"
        + (topic == null || topic.isBlank() ? "" : " (" + topic + ")")
        + ". Answer it here.]\n\n" + message;
  }

  /** What the reply's card in the main chat shows and opens. */
  private static ObjectNode threadCard(ConversationEntity thread, AgentEntity lead, boolean resumed) {
    ObjectNode card = Json.obj()
        .put("conversationId", thread.getId().toString())
        .put("title", thread.getTitle())
        .put("topic", thread.getTopic())
        .put("leadKey", lead == null ? "jizza" : lead.getKey())
        .put("leadName", lead == null ? "Jizza" : lead.getName())
        .put("resumed", resumed);
    if (lead != null) card.put("leadId", lead.getId().toString());
    return card;
  }
}
