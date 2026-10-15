package com.jizza.engine.chat.turn;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.activity.Activity;
import com.jizza.engine.api.activity.ActivityTypes;
import com.jizza.engine.api.activity.JobCancelledException;
import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.chat.ChatService;
import com.jizza.engine.api.chat.JobDto;
import com.jizza.engine.api.chat.JobEventDto;
import com.jizza.engine.api.chat.TurnRef;
import com.jizza.engine.api.chat.TurnSubmission;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.error.JizzaException;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.llm.Msg;
import com.jizza.engine.api.memory.MemoryLearner;
import com.jizza.engine.api.orchestrator.AssistantReply;
import com.jizza.engine.api.orchestrator.Orchestrator;
import com.jizza.engine.api.orchestrator.UiAction;
import com.jizza.engine.api.realtime.RealtimeGateway;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.chat.activity.JobRecorder;
import com.jizza.engine.chat.activity.JobSummaries;
import com.jizza.engine.persistence.entity.ChatJobEntity;
import com.jizza.engine.persistence.repository.JobEventRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
  private final ExecutorService runner =
      Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("jizza-turn-", 0).factory());

  /** Jobs still running, plus incognito jobs kept briefly after they finish. */
  private final Map<UUID, Active> active = new ConcurrentHashMap<>();

  private record Active(UUID userId, JobRecorder recorder, CompletableFuture<AssistantReply> reply,
                        boolean incognito, String title, Instant startedAt) {}

  public TurnRunner(TurnStore store, Orchestrator orchestrator, JobEventRepository events,
                    AuditLog audit, ObjectProvider<RealtimeGateway> realtime,
                    ObjectProvider<MemoryLearner> learner) {
    this.store = store;
    this.orchestrator = orchestrator;
    this.events = events;
    this.audit = audit;
    this.realtime = realtime;
    this.learner = learner;
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
      return new Started(ref, launch(userId, ref, s, List.of(), false));
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
    return new Started(ref, launch(userId, ref, s, history, true));
  }

  private CompletableFuture<AssistantReply> launch(UUID userId, TurnRef ref, TurnSubmission s,
                                                   List<Msg> history, boolean persist) {
    JobRecorder recorder = new JobRecorder(ref.jobId(), userId, ref.conversationId(), persist,
        events, realtime.getIfAvailable());
    CompletableFuture<AssistantReply> reply = new CompletableFuture<>();
    active.put(ref.jobId(), new Active(userId, recorder, reply, !persist,
        JobSummaries.title(s.text()), Instant.now()));

    recorder.emit(event(ActivityTypes.JOB_STARTED, p -> {
      p.put("title", JobSummaries.title(s.text()));
      if (ref.userMessageId() != null) p.put("userMessageId", ref.userMessageId().toString());
      if (ref.assistantMessageId() != null) p.put("assistantMessageId", ref.assistantMessageId().toString());
    }));
    runner.submit(() -> run(userId, ref, s, history, recorder, reply));
    return reply;
  }

  private void run(UUID userId, TurnRef ref, TurnSubmission s, List<Msg> history,
                   JobRecorder recorder, CompletableFuture<AssistantReply> reply) {
    boolean persist = !ref.incognito();
    Orchestrator.Turn turn = null;
    try {
      turn = Activity.run(recorder, () -> orchestrator.respond(userId,
          new Orchestrator.TurnInput(s.text(), s.screen(), history, ref.userMessageId())));
      Orchestrator.Turn done = turn;
      AssistantReply answer = new AssistantReply(ref.conversationId(), ref.assistantMessageId(),
          done.text(), contributors(done), done.actions());
      String summary = JobSummaries.summarize(recorder.all());

      if (persist) {
        store.finish(ref.jobId(), ChatJobEntity.COMPLETED, done.text(), metadata(ref, answer), summary, done);
      }
      recorder.emit(event(ActivityTypes.JOB_COMPLETED, p -> {
        p.put("text", done.text());
        p.put("summary", summary);
        p.set("contributors", Json.toNode(answer.contributors()));
        p.set("actions", Json.toNode(answer.actions()));
        p.put("inputTokens", done.inputTokens()).put("outputTokens", done.outputTokens());
      }));
      if (persist) {
        // Durable facts in what the user just said are promoted to long-term memory (off this
        // thread unless configured otherwise). Incognito turns never reach here.
        learner.ifAvailable(l -> l.afterTurn(new MemoryLearner.Turn(userId, ref.conversationId(),
            ref.userMessageId(), s.text(), done.text())));
      }
      reply.complete(answer);
    } catch (JobCancelledException e) {
      String partial = recorder.partialText();
      String summary = JobSummaries.summarize(recorder.all()) + " Stopped by you.";
      if (persist) store.finish(ref.jobId(), ChatJobEntity.CANCELLED, partial, null, summary, null);
      audit.record(userId, ActorType.USER, userId, "chat.turn.cancel", ref.jobId().toString(), null);
      recorder.emit(event(ActivityTypes.JOB_CANCELLED, p -> p.put("text", partial).put("summary", summary)));
      reply.completeExceptionally(new JizzaException(org.springframework.http.HttpStatus.CONFLICT,
          "turn-cancelled", "Stopped", "The turn was stopped before it finished"));
    } catch (RuntimeException e) {
      log.warn("Chat turn {} failed", ref.jobId(), e);
      String title = e instanceof JizzaException je ? je.title() : "Something went wrong";
      String detail = e.getMessage() == null ? "The turn failed" : e.getMessage();
      String type = e instanceof JizzaException je ? je.type() : "turn-failed";
      if (persist) {
        try {
          store.finish(ref.jobId(), ChatJobEntity.FAILED, detail, null,
              JobSummaries.summarize(recorder.all()) + " Stopped: " + detail, null);
        } catch (RuntimeException inner) {
          log.warn("Could not record the failure of turn {}", ref.jobId(), inner);
        }
      }
      recorder.emit(event(ActivityTypes.JOB_FAILED, p -> p.put("title", title).put("detail", detail)
          .put("problemType", type)));
      reply.completeExceptionally(e);
    } finally {
      if (persist) active.remove(ref.jobId());
    }
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
