package com.jizza.engine.chat.turn;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.activity.Activity;
import com.jizza.engine.api.activity.ActivityTypes;
import com.jizza.engine.api.activity.JobCancelledException;
import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.chat.ChatFileAccess;
import com.jizza.engine.api.chat.ChatService;
import com.jizza.engine.api.chat.JobDto;
import com.jizza.engine.api.chat.JobEventDto;
import com.jizza.engine.api.chat.TurnRef;
import com.jizza.engine.api.chat.TurnSubmission;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.error.FriendlyErrors;
import com.jizza.engine.api.error.JizzaException;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.chat.ChatShortcut;
import com.jizza.engine.api.llm.Msg;
import com.jizza.engine.api.memory.MemoryLearner;
import com.jizza.engine.api.memory.OutputLearner;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import com.jizza.engine.api.orchestrator.AssistantReply;
import com.jizza.engine.api.orchestrator.Orchestrator;
import com.jizza.engine.api.orchestrator.UiAction;
import com.jizza.engine.api.orchestrator.UserSteps;
import com.jizza.engine.api.realtime.RealtimeGateway;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.chat.activity.JobRecorder;
import com.jizza.engine.chat.activity.JobSummaries;
import com.jizza.engine.chat.thread.ThreadGuideKeeper;
import com.jizza.engine.chat.thread.ThreadService;
import com.jizza.engine.api.chat.ChatScope;
import com.jizza.engine.persistence.entity.ChatJobEntity;
import com.jizza.engine.persistence.repository.JobEventRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Runs each chat turn as a job in the background (R43/R53).
 *
 * <p>Sending a message stores it, starts the job and returns at once. The job runs Jizza with a
 * {@link JobRecorder} current on its thread, so everything it does -- Jizza's words as they are
 * written, its tasks to specialists and their answers, the APIs they call, the memory they are
 * given -- reaches the user's socket as it happens and is kept for the Activity panel. The reply is
 * the job's last event.
 */
@Service
public class TurnRunner implements ChatService {

  private static final Logger log = LoggerFactory.getLogger(TurnRunner.class);

  /** How long an incognito job's events stay in memory for a client to catch up. */
  private static final Duration INCOGNITO_RETENTION = Duration.ofMinutes(10);

  private final TurnStore store;
  private final Orchestrator orchestrator;
  private final JobEventRepository events;
  private final AuditLog audit;
  private final ObjectProvider<RealtimeGateway> realtime;
  private final ObjectProvider<MemoryLearner> learner;
  private final ObjectProvider<OutputLearner> outputLearner;
  private final TransactionTemplate eventTx;
  private final ExecutorService runner =
      Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("jizza-turn-", 0).factory());

  /** Jobs still running, plus incognito jobs kept briefly after they finish. */
  private final Map<UUID, Active> active = new ConcurrentHashMap<>();

  private record Active(UUID userId, JobRecorder recorder, CompletableFuture<AssistantReply> reply,
                        boolean incognito, String title, Instant startedAt) {}

  private final ObjectProvider<ConversationUpkeep> upkeep;
  private final ObjectProvider<ThreadService> threads;
  private final ObjectProvider<ThreadGuideKeeper> guides;
  private final ObjectProvider<com.jizza.engine.chat.thread.ThreadCoordinator> coordinator;
  private ObjectProvider<ChatShortcut> shortcuts;

  /** Slash commands the app answers itself, such as {@code /run}; optional for isolated tests. */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setShortcuts(ObjectProvider<ChatShortcut> shortcuts) {
    this.shortcuts = shortcuts;
  }

  public TurnRunner(TurnStore store, Orchestrator orchestrator, JobEventRepository events,
                    AuditLog audit, ObjectProvider<RealtimeGateway> realtime,
                    ObjectProvider<MemoryLearner> learner, ObjectProvider<OutputLearner> outputLearner,
                    PlatformTransactionManager transactions, ObjectProvider<ConversationUpkeep> upkeep,
                    ObjectProvider<ThreadService> threads, ObjectProvider<ThreadGuideKeeper> guides,
                    ObjectProvider<com.jizza.engine.chat.thread.ThreadCoordinator> coordinator) {
    this.coordinator = coordinator;
    this.upkeep = upkeep;
    this.threads = threads;
    this.guides = guides;
    this.store = store;
    this.orchestrator = orchestrator;
    this.events = events;
    this.audit = audit;
    this.realtime = realtime;
    this.learner = learner;
    this.outputLearner = outputLearner;
    this.eventTx = new TransactionTemplate(transactions);
    this.eventTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  @Override
  public TurnRef submit(UUID userId, TurnSubmission submission) {
    return start(userId, submission).ref();
  }

  @Override
  public AssistantReply submitAndWait(UUID userId, TurnSubmission submission) {
    Started started = start(userId, submission);
    try {
      return started.reply().join();
    } catch (CompletionException e) {
      if (e.getCause() instanceof RuntimeException cause) throw cause;
      throw e;
    }
  }

  private record Started(TurnRef ref, CompletableFuture<AssistantReply> reply) {}

  private Started start(UUID userId, TurnSubmission s) {
    if (s.text() == null || s.text().isBlank()) throw new ValidationException("The message is empty");
    purgeIncognito();

    if (s.incognito()) {
      UUID jobId = UUID.randomUUID();
      TurnRef ref = new TurnRef(jobId, null, null, null, true);
      return new Started(ref, launch(userId, ref, s, List.of(), false, null));
    }

    var existing = store.existing(userId, s.conversationId(), s.clientMessageId());
    if (existing.isPresent()) {
      Active running = active.get(existing.get().jobId());
      return new Started(existing.get(),
          running != null ? running.reply() : CompletableFuture.completedFuture(replyFor(existing.get())));
    }

    TurnRef ref = store.begin(userId, s.conversationId(), s.clientMessageId(), s.text());
    audit.record(userId, ActorType.USER, userId, "chat.message", ref.conversationId().toString(),
        Json.obj().put("messageId", ref.userMessageId().toString()).put("jobId", ref.jobId().toString()));
    List<Msg> history = store.history(ref.conversationId(), ref.userMessageId());
    return new Started(ref, launch(userId, ref, s, history, true, null));
  }

  /**
   * Something happened in a conversation that nobody typed (an approval was answered), and Jizza
   * should report on it in its own words. {@code note} is the line the conversation shows;
   * {@code prepare} runs first inside the new job (so whatever it does shows in Activity) and
   * returns what Jizza is told. Waits its turn if Jizza is mid-reply in that conversation.
   */
  public TurnRef continueConversation(UUID userId, UUID conversationId, String note,
                                      ObjectNode noteData, Supplier<String> prepare) {
    return continueConversation(userId, conversationId, "continuation", null, note, noteData, prepare);
  }

  /**
   * As above, for any job nobody typed: {@code origin} says what started it ({@code signal} when a
   * specialist flagged something on a plan), {@code planId} the plan it concerns. A null
   * conversation starts a new one.
   */
  public TurnRef continueConversation(UUID userId, UUID conversationId, String origin, UUID planId,
                                      String note, ObjectNode noteData, Supplier<String> prepare) {
    TurnRef ref = null;
    Instant giveUp = Instant.now().plus(CONTINUATION_WAIT);
    while (ref == null) {
      try {
        ref = store.beginContinuation(userId, conversationId, origin, planId, note, noteData, note);
      } catch (com.jizza.engine.api.error.ConflictException busy) {
        if (Instant.now().isAfter(giveUp)) throw busy;
        try {
          Thread.sleep(500);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw busy;
        }
      }
    }
    List<Msg> history = store.history(ref.conversationId(), ref.userMessageId());
    // On the chat screen, so Jizza can put a question with one-tap answers to the user.
    TurnSubmission s = new TurnSubmission(ref.conversationId(), null, note, "chat", false);
    launch(userId, ref, s, history, true, new Continuation(origin, prepare, noteData));
    return ref;
  }

  /**
   * A message the user wrote somewhere else, moved here and answered: {@code handoff} from the
   * main chat into a thread, {@code handback} from a thread to the main chat. The conversation
   * keeps {@code text} as the user's message; the agent answering is told {@code input}, which
   * says where it came from. Nothing is learned from it again: it was learned where it was typed.
   *
   * @throws com.jizza.engine.api.error.ConflictException while a reply is running there
   */
  public TurnRef handOff(UUID userId, UUID conversationId, String text, String input, String origin) {
    TurnRef ref = store.begin(userId, conversationId, UUID.randomUUID(), text, origin);
    audit.record(userId, ActorType.AGENT, null, "chat.message", ref.conversationId().toString(),
        Json.obj().put("messageId", ref.userMessageId().toString()).put("jobId", ref.jobId().toString())
            .put("origin", origin));
    List<Msg> history = store.history(ref.conversationId(), ref.userMessageId());
    TurnSubmission s = new TurnSubmission(ref.conversationId(), null, text, "chat", false);
    launch(userId, ref, s, history, true, new Continuation(origin, () -> input, null));
    return ref;
  }

  /** How long a continuation waits for a reply already running in its conversation. */
  private static final Duration CONTINUATION_WAIT = Duration.ofMinutes(5);

  private record Continuation(String origin, Supplier<String> prepare, ObjectNode noteData) {}

  private CompletableFuture<AssistantReply> launch(UUID userId, TurnRef ref, TurnSubmission s,
                                                   List<Msg> history, boolean persist,
                                                   Continuation continuation) {
    String origin = continuation != null ? continuation.origin() : persist ? "chat" : "incognito";
    JobRecorder recorder = new JobRecorder(ref.jobId(), userId, ref.conversationId(), origin, persist,
        events, eventTx, realtime.getIfAvailable());
    CompletableFuture<AssistantReply> reply = new CompletableFuture<>();
    active.put(ref.jobId(), new Active(userId, recorder, reply, !persist,
        JobSummaries.title(s.text()), Instant.now()));

    recorder.emit(event(ActivityTypes.JOB_STARTED, p -> {
      p.put("title", JobSummaries.title(s.text()));
      if (ref.userMessageId() != null) p.put("userMessageId", ref.userMessageId().toString());
      if (ref.assistantMessageId() != null) p.put("assistantMessageId", ref.assistantMessageId().toString());
      if (continuation != null) {
        p.put("origin", continuation.origin()).put("note", s.text());
        if (continuation.noteData() != null) p.set("noteData", continuation.noteData());
      }
    }));
    runner.submit(() -> run(userId, ref, s, history, recorder, reply, continuation));
    return reply;
  }

  private void run(UUID userId, TurnRef ref, TurnSubmission s, List<Msg> history,
                   JobRecorder recorder, CompletableFuture<AssistantReply> reply,
                   Continuation continuation) {
    boolean persist = !ref.incognito();
    Orchestrator.Turn turn = null;
    try {
      // A slash command such as /run is answered here, with no model call at all.
      Optional<Orchestrator.Turn> direct = continuation == null
          ? shortcut(userId, ref.conversationId(), s.text()) : Optional.empty();
      String input = continuation == null ? s.text() : Activity.run(recorder, continuation.prepare());
      // Where this turn is: the main chat, where Jizza coordinates, or a thread and its lead.
      ThreadService.Framing framing = ref.conversationId() == null ? null
          : threads.getObject().frame(userId, ref.conversationId());
      List<Msg> told = framing == null || framing.preamble() == null ? history
          : prepend(Msg.system(framing.preamble()), history);
      Orchestrator.TurnInput turnInput =
          new Orchestrator.TurnInput(input, s.screen(), told, ref.userMessageId(), s.context());
      // The reply may read the PDFs and photos the user attached; routing it does not.
      Supplier<Orchestrator.Turn> respond = () -> ChatFileAccess.run(userId, () ->
          framing != null && framing.lead() != null
              ? orchestrator.respondAs(userId, framing.lead().getId(), turnInput)
              : orchestrator.respond(userId, turnInput));
      // Jizza coordinates the conversation's threads: a message the user typed goes first to
      // where it belongs, and is answered there instead of here.
      Optional<Orchestrator.Turn> routed = direct.isPresent() ? direct
          : continuation == null && framing != null
          ? coordinator.getIfAvailable() == null ? Optional.empty()
              : Activity.run(recorder, () -> coordinator.getObject().coordinate(framing.scope(), s.text(), history))
          : Optional.empty();
      turn = routed.isPresent() ? routed.get() : Activity.run(recorder, () -> framing == null ? respond.get()
          : ChatScope.run(framing.scope(), () -> withThreadActions(respond.get(), framing.scope())));
      Orchestrator.Turn done = routed.isPresent() ? turn : withSignInSteps(turn, recorder.all());
      AssistantReply answer = new AssistantReply(ref.conversationId(), ref.assistantMessageId(),
          done.text(), contributors(done), done.actions());
      // A message Jizza moved on is answered by Jizza saying where it went, not by the lead.
      ObjectNode lead = framing == null || framing.lead() == null || routed.isPresent() ? null : Json.obj()
          .put("agentId", framing.lead().getId().toString())
          .put("key", framing.lead().getKey())
          .put("name", framing.lead().getName());
      String summary = JobSummaries.summarize(recorder.all());
      // A reply that left a call waiting for the user's approval is done talking, not done working.
      String status = recorder.all().stream()
          .anyMatch(e -> ActivityTypes.APPROVAL_REQUIRED.equals(e.type()))
          ? ChatJobEntity.AWAITING_USER : ChatJobEntity.COMPLETED;

      // The closing event is written before the job row says it finished, so anyone who sees a
      // finished job can also read how it ended.
      recorder.emit(event(ActivityTypes.JOB_COMPLETED, p -> {
        p.put("text", done.text());
        p.put("summary", summary);
        p.set("contributors", Json.toNode(answer.contributors()));
        p.set("actions", Json.toNode(answer.actions()));
        p.put("inputTokens", done.inputTokens()).put("outputTokens", done.outputTokens());
        p.put("status", status);
        if (lead != null) p.set("lead", lead);
      }));
      if (persist) {
        ObjectNode meta = metadata(ref, answer);
        if (lead != null) meta.set("lead", lead);
        store.finish(ref.jobId(), status, done.text(), meta, summary, done);
      }
      if (persist) {
        // Durable facts in what the user said, and in what the job fetched, are kept (off this
        // thread unless configured otherwise). Bound to the job so what is saved shows under it.
        // Incognito turns never reach here: they read memory but never write it.
        Activity.run(recorder, () -> {
          // A continuation's input is a system event, not the user's words: nothing to learn there,
          // except a message moved here, which is. A message moved out of this chat is learned
          // where it went, so a fact belongs to the thread that worked on it.
          boolean moved = continuation != null
              && ("handoff".equals(continuation.origin()) || "handback".equals(continuation.origin()));
          boolean movedAway = answer.actions() != null && answer.actions().stream()
              .anyMatch(a -> UiAction.THREAD_OPENED.equals(a.type())
                  || UiAction.THREAD_HANDBACK.equals(a.type()));
          if ((continuation == null || moved) && !movedAway && direct.isEmpty()) {
            learner.ifAvailable(l -> l.afterTurn(new MemoryLearner.Turn(userId, ref.conversationId(),
                ref.userMessageId(), s.text(), done.text())));
          }
          List<OutputLearner.Finding> found = findings(recorder.all());
          if (!found.isEmpty()) {
            outputLearner.ifAvailable(l -> l.afterJob(new OutputLearner.JobOutput(userId,
                ref.conversationId(), ref.jobId(), s.text(), found)));
          }
          // A title after the first exchange, and a summary once the chat outgrows the window.
          upkeep.ifAvailable(u -> u.afterTurn(userId, ref.conversationId()));
          // A thread's guide learns from each exchange in it.
          if (framing != null && framing.thread()) {
            guides.ifAvailable(g -> g.afterTurn(userId, ref.conversationId(), s.text(), done.text()));
          }
          return null;
        });
      }
      reply.complete(answer);
    } catch (JobCancelledException e) {
      String partial = recorder.partialText();
      String summary = JobSummaries.summarize(recorder.all()) + " Stopped by you.";
      recorder.emit(event(ActivityTypes.JOB_CANCELLED, p -> p.put("text", partial).put("summary", summary)));
      if (persist) store.finish(ref.jobId(), ChatJobEntity.CANCELLED, partial, null, summary, null);
      audit.record(userId, ActorType.USER, userId, "chat.turn.cancel", ref.jobId().toString(), null);
      reply.completeExceptionally(new JizzaException(org.springframework.http.HttpStatus.CONFLICT,
          "turn-cancelled", "Stopped", "The turn was stopped before it finished"));
    } catch (RuntimeException e) {
      // Our own failures (the model provider, a bug) are logged in full with a reference, and the
      // person sees a plain message quoting it; errors about what they did pass through as they are.
      String reference = FriendlyErrors.isInternal(e) ? FriendlyErrors.newReference() : null;
      if (reference != null) {
        log.error("Chat turn {} failed (reference {})", ref.jobId(), reference, e);
      } else {
        log.warn("Chat turn {} failed: {}", ref.jobId(), e.getMessage());
      }
      JizzaException shown = FriendlyErrors.forPerson(e, reference);
      String title = shown.title();
      String detail = shown.getMessage() == null ? "The turn failed" : shown.getMessage();
      String type = e instanceof JizzaException ? shown.type() : "turn-failed";
      String summary = JobSummaries.summarize(recorder.all()) + " Stopped: " + detail;
      recorder.emit(event(ActivityTypes.JOB_FAILED, p -> p.put("title", title).put("detail", detail)
          .put("problemType", type)));
      if (persist) {
        try {
          store.finish(ref.jobId(), ChatJobEntity.FAILED, detail, null, summary, null);
        } catch (RuntimeException inner) {
          log.warn("Could not record the failure of turn {}", ref.jobId(), inner);
        }
      }
      reply.completeExceptionally(shown);
    } finally {
      if (persist) active.remove(ref.jobId());
    }
  }

  private Optional<Orchestrator.Turn> shortcut(UUID userId, UUID conversationId, String text) {
    if (shortcuts == null || text == null || !text.stripLeading().startsWith("/")) return Optional.empty();
    for (ChatShortcut shortcut : shortcuts.orderedStream().toList()) {
      Optional<Orchestrator.Turn> turn = shortcut.handle(userId, conversationId, text);
      if (turn.isPresent()) return turn;
    }
    return Optional.empty();
  }

  @Override
  public JobDto job(UUID userId, UUID jobId, int afterSeq) {
    Active running = active.get(jobId);
    if (running != null && running.incognito()) {
      if (!running.userId().equals(userId)) throw new NotFoundException("Job", jobId);
      String status = !running.reply().isDone() ? ChatJobEntity.RUNNING
          : running.reply().isCompletedExceptionally() ? ChatJobEntity.FAILED : ChatJobEntity.COMPLETED;
      return new JobDto(jobId, null, "chat", running.title(), null, status, null, null,
          running.startedAt(), null, running.recorder().keptAfter(afterSeq),
          running.recorder().partialText());
    }
    ChatJobEntity job = store.job(userId, jobId);
    List<JobEventDto> after = events.findByJobIdAndSeqGreaterThanOrderBySeqAsc(jobId, afterSeq).stream()
        .map(e -> new JobEventDto(e.getSeq(), e.getType(), e.getAgentId(), e.getAgentName(),
            e.getPayload(), e.getAt()))
        .toList();
    return toDto(job, after, running == null ? null : running.recorder().partialText());
  }

  @Override
  public List<JobDto> jobs(UUID userId, UUID conversationId) {
    return store.jobs(userId, conversationId).stream()
        .map(j -> toDto(j, List.of(), null))
        .toList();
  }

  @Override
  public void cancel(UUID userId, UUID jobId) {
    Active running = active.get(jobId);
    if (running == null) {
      if (!active.containsKey(jobId)) store.job(userId, jobId); // 404 for someone else's job
      return;
    }
    if (!running.userId().equals(userId)) throw new NotFoundException("Job", jobId);
    running.recorder().cancel();
  }

  private static JobDto toDto(ChatJobEntity j, List<JobEventDto> events, String partial) {
    return new JobDto(j.getId(), j.getConversationId(), j.getOrigin(), j.getTitle(), j.getSummary(),
        j.getStatus(), j.getUserMessageId(), j.getAssistantMessageId(), j.getStartedAt(),
        j.getFinishedAt(), events, partial);
  }

  /** What the job fetched, as the summaries the Activity trail already shows, never raw payloads. */
  static List<OutputLearner.Finding> findings(List<JobEventDto> events) {
    List<OutputLearner.Finding> out = new java.util.ArrayList<>();
    for (JobEventDto e : events) {
      if (ActivityTypes.OUTPUT.equals(e.type())) {
        out.add(new OutputLearner.Finding(e.agentId(), e.agentName(),
            e.payload().path("service").asText(null), e.payload().path("text").asText("")));
      } else if (ActivityTypes.AGENT_MESSAGE.equals(e.type())
          && "answer".equals(e.payload().path("kind").asText())) {
        String text = e.payload().path("text").asText("");
        out.add(new OutputLearner.Finding(e.agentId(), e.agentName(), null,
            text.length() <= 2000 ? text : text.substring(0, 2000)));
      }
    }
    // Only what a service returned is new information; a specialist answering from memory is not.
    return out.stream().anyMatch(f -> f.service() != null) ? out : List.of();
  }

  /** A reply already given, for a retried send that arrives after its turn finished. */
  private AssistantReply replyFor(TurnRef ref) {
    return new AssistantReply(ref.conversationId(), ref.assistantMessageId(),
        store.content(ref.assistantMessageId()), List.of());
  }

  private static List<AssistantReply.Contribution> contributors(Orchestrator.Turn turn) {
    return turn.delegations().stream()
        .map(d -> new AssistantReply.Contribution(d.agentId(), d.agentName(), d.task()))
        .toList();
  }

  private static List<Msg> prepend(Msg first, List<Msg> rest) {
    List<Msg> all = new java.util.ArrayList<>(rest.size() + 1);
    all.add(first);
    all.addAll(rest);
    return all;
  }

  /** The reply with what the thread tools asked the screen to show (a new thread, a hand-back). */
  private static Orchestrator.Turn withThreadActions(Orchestrator.Turn turn, ChatScope.Scope scope) {
    List<UiAction> recorded = ChatScope.actions(scope);
    if (recorded.isEmpty()) return turn;
    List<UiAction> all = new java.util.ArrayList<>(turn.actions());
    all.addAll(recorded);
    return new Orchestrator.Turn(turn.text(), turn.delegations(), all, turn.inputTokens(),
        turn.outputTokens());
  }

  /**
   * Sign-in links the job's tools handed back, as a card of steps with buttons on the reply. A
   * link already on a card the agent made is left there.
   */
  public static Orchestrator.Turn withSignInSteps(Orchestrator.Turn turn, List<JobEventDto> events) {
    java.util.Set<String> shown = new java.util.HashSet<>();
    turn.actions().forEach(a -> shown.addAll(UserSteps.urls(a)));
    java.util.Map<String, UserSteps.SignInLink> links = new java.util.LinkedHashMap<>();
    for (JobEventDto e : events) {
      if (!ActivityTypes.SIGN_IN_LINK.equals(e.type())) continue;
      String url = e.payload().path("url").asText("");
      if (url.isEmpty() || shown.contains(url)) continue;
      links.putIfAbsent(url, new UserSteps.SignInLink(url, e.payload().path("service").asText("the service")));
    }
    if (links.isEmpty()) return turn;
    List<UiAction> all = new java.util.ArrayList<>(turn.actions());
    all.add(UserSteps.signInCard(List.copyOf(links.values()).subList(0, Math.min(links.size(), 3))));
    return new Orchestrator.Turn(turn.text(), turn.delegations(), all, turn.inputTokens(),
        turn.outputTokens());
  }

  /** What the client needs to redraw this reply exactly as it looked live. */
  private static ObjectNode metadata(TurnRef ref, AssistantReply answer) {
    ObjectNode meta = Json.obj();
    meta.put("jobId", ref.jobId().toString());
    meta.set("contributors", Json.toNode(answer.contributors()));
    ArrayNode actions = meta.putArray("actions");
    for (UiAction action : answer.actions()) actions.add(Json.toNode(action));
    return meta;
  }

  private static com.jizza.engine.api.activity.ActivityEvent event(
      String type, java.util.function.Consumer<ObjectNode> payload) {
    ObjectNode node = Json.obj();
    payload.accept(node);
    return new com.jizza.engine.api.activity.ActivityEvent(type, null, null, node);
  }

  private void purgeIncognito() {
    Instant cutoff = Instant.now().minus(INCOGNITO_RETENTION);
    active.entrySet().removeIf(e -> e.getValue().incognito() && e.getValue().reply().isDone()
        && e.getValue().startedAt().isBefore(cutoff));
  }

  @jakarta.annotation.PreDestroy
  void shutdown() {
    runner.shutdown();
  }
}
