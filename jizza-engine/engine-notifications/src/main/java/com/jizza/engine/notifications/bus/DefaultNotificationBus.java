package com.jizza.engine.notifications.bus;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.device.SealedAnswers;
import com.jizza.engine.api.dto.NotificationDto;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.enums.EscalationState;
import com.jizza.engine.api.enums.NotificationKind;
import com.jizza.engine.api.enums.NotificationPriority;
import com.jizza.engine.api.enums.NotificationStatus;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.event.DeviceHelpAnswered;
import com.jizza.engine.api.event.InfoRaised;
import com.jizza.engine.api.event.InstructionRequired;
import com.jizza.engine.api.event.InstructionSubmitted;
import com.jizza.engine.api.event.PermissionAnswered;
import com.jizza.engine.api.event.PermissionRequested;
import com.jizza.engine.api.event.ReviewAnswered;
import com.jizza.engine.api.event.WorkflowReviewRequested;
import com.jizza.engine.api.notification.Answer;
import com.jizza.engine.api.notification.NotificationBus;
import com.jizza.engine.api.notification.NotificationContext;
import com.jizza.engine.api.orchestrator.AgentDirectory;
import com.jizza.engine.api.procedure.ProcedureService;
import com.jizza.engine.api.realtime.RealtimeGateway;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.workflow.WorkflowEngine;
import com.jizza.engine.notifications.config.NotificationProperties;
import com.jizza.engine.persistence.entity.NotificationEntity;
import com.jizza.engine.persistence.repository.NotificationRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import com.jizza.engine.api.enums.RunStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Everything an agent has to say to the user goes through here (R37).
 *
 * <p>Items are always attributed to the agent that raised them by name, and always delivered as
 * Jizza relaying them -- no specialist reaches the user directly. The four kinds differ only in
 * what answering them does: an approval releases a paused run, a review approves or rejects a
 * procedure, and an instruction resumes a run at the node that asked for it.
 */
@Service
public class DefaultNotificationBus implements NotificationBus {

  private static final Logger log = LoggerFactory.getLogger(DefaultNotificationBus.class);

  private final NotificationRepository notifications;
  private final AgentDirectory agents;
  private final ObjectProvider<WorkflowEngine> workflowEngine;
  private final ObjectProvider<ProcedureService> procedures;
  private final ObjectProvider<RealtimeGateway> realtime;
  private final ApplicationEventPublisher events;
  private final AuditLog audit;
  private final NotificationProperties props;
  private PlatformTransactionManager transactionManager;

  public DefaultNotificationBus(NotificationRepository notifications, AgentDirectory agents,
                                ObjectProvider<WorkflowEngine> workflowEngine,
                                ObjectProvider<ProcedureService> procedures,
                                ObjectProvider<RealtimeGateway> realtime,
                                ApplicationEventPublisher events, AuditLog audit,
                                NotificationProperties props) {
    this.notifications = notifications;
    this.agents = agents;
    this.workflowEngine = workflowEngine;
    this.procedures = procedures;
    this.realtime = realtime;
    this.events = events;
    this.audit = audit;
    this.props = props;
  }

  /** Optional so tests can build the bus without a database. */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setTransactionManager(PlatformTransactionManager transactionManager) {
    this.transactionManager = transactionManager;
  }

  /**
   * Joins the caller's transaction. A notification points at the run, task or workflow it is about,
   * so it cannot be committed before that row exists -- and a run that rolls back should not leave
   * the user an item asking about work that never happened. The realtime push is fired here rather
   * than after commit; a client that acts on it re-reads through the API, which is the same thing
   * it does on reconnect.
   */
  @Override
  @Transactional(propagation = Propagation.REQUIRED)
  public NotificationDto raise(NotificationKind kind, UUID sourceAgentId, NotificationContext ctx) {
    NotificationEntity entity = new NotificationEntity();
    entity.setUserId(ctx.userId());
    entity.setSourceAgentId(sourceAgentId);
    entity.setKind(kind);
    entity.setBody(ctx.body());
    entity.setContext(ctx.detail());
    entity.setTaskId(ctx.taskId());
    entity.setWorkflowId(ctx.workflowId());
    entity.setRunId(ctx.runId());
    entity.setStatus(NotificationStatus.UNREAD);
    entity.setEscalationState(EscalationState.NONE);
    entity.setPriority(ctx.priority() == null ? NotificationPriority.NORMAL : ctx.priority());
    // Only blocking items expire: an informational note has nothing to hold up.
    if (kind.blocking()) {
      Duration ttl = ctx.expiresIn() == null ? props.expiryFor(kind) : ctx.expiresIn();
      entity.setExpiresAt(Instant.now().plus(ttl));
    }
    notifications.save(entity);

    NotificationDto dto = toDto(entity);
    audit.record(ctx.userId(), ActorType.AGENT, sourceAgentId, "notification.raised",
        kind.name().toLowerCase(java.util.Locale.ROOT),
        Json.obj().put("notificationId", entity.getId().toString()));

    publishRaised(kind, dto);
    // Something urgent is not left for the next reminder sweep: it is nudged out of the app as
    // soon as the work that raised it commits.
    if (entity.getPriority() == NotificationPriority.URGENT) {
      events.publishEvent(new UrgentNotificationRaised(entity.getId()));
    }
    // Pushed immediately so a paused run surfaces the moment it stops, not at the next poll (R43).
    realtime.ifAvailable(gateway -> gateway.publish(ctx.userId(), "notifications", dto));
    return dto;
  }

  @Override
  @Transactional
  public NotificationDto answer(UUID notificationId, Answer answer) {
    NotificationEntity entity = require(notificationId);
    if (entity.getStatus() == NotificationStatus.APPROVED
        || entity.getStatus() == NotificationStatus.DENIED
        || entity.getStatus() == NotificationStatus.SUBMITTED) {
      throw new ValidationException("That item has already been answered");
    }
    if (entity.getStatus() == NotificationStatus.EXPIRED) {
      throw new ValidationException("That item expired and can no longer be answered");
    }
    if (!entity.getKind().blocking()) {
      throw new ValidationException("An informational item has nothing to answer");
    }

    if (answer.kind() == Answer.Kind.GO_BACK) return answerGoBack(entity, answer);
    return switch (entity.getKind()) {
      case PERMISSION_REQUEST -> answerPermission(entity, answer);
      case WORKFLOW_REVIEW -> answerReview(entity, answer);
      case INSTRUCTION_REQUIRED -> answerInstruction(entity, answer);
      case INFO -> throw new ValidationException("An informational item has nothing to answer");
    };
  }

  /**
   * Back, on a run's question whose step allows going back: the run goes back to the earlier
   * step named (or the first the item offers) and asks there again. Only steps the item offered
   * can be named.
   */
  private NotificationDto answerGoBack(NotificationEntity entity, Answer answer) {
    JsonNode offered = entity.getContext() == null ? null : entity.getContext().get("back");
    if (entity.getRunId() == null || offered == null || !offered.isArray() || offered.isEmpty()) {
      throw new ValidationException("This item has no earlier step to go back to");
    }
    String wanted = blankToNull(answer.instruction());
    JsonNode target = null;
    for (JsonNode step : offered) {
      if (wanted == null || wanted.equals(step.path("node").asText())) {
        target = step;
        break;
      }
    }
    if (target == null) {
      throw new ValidationException("That step is not one this item can go back to");
    }
    if (runHasEnded(entity.getRunId())) {
      close(entity, RUN_ALREADY_ENDED);
      return toDto(entity);
    }
    String label = target.path("label").asText(target.path("node").asText());
    entity.setStatus(NotificationStatus.SUBMITTED);
    entity.setSubmittedInstruction("Go back to " + label);
    entity.setAnsweredAt(Instant.now());
    entity.setSnoozedUntil(null);
    notifications.save(entity);

    audit.record(entity.getUserId(), ActorType.USER, null, "notification.went_back",
        entity.getId().toString(), Json.obj().put("to", target.path("node").asText()));
    resumeRun(entity, Json.obj().put("back", target.path("node").asText()));
    return toDto(entity);
  }

  /** Longest answer passed on to a device (a CAPTCHA's text, a code, a pasted sign-in link). */
  static final int MAX_DEVICE_ANSWER = 4_000;

  private NotificationDto answerPermission(NotificationEntity entity, Answer answer) {
    JsonNode help = entity.getContext() == null ? null : entity.getContext().get("deviceHelp");
    if (help != null && help.isObject()) return answerDeviceHelp(entity, help, answer);
    boolean approved = answer.kind() == Answer.Kind.APPROVE;
    if (approved) requireFilledIn(entity, answer.inputs());
    entity.setStatus(approved ? NotificationStatus.APPROVED : NotificationStatus.DENIED);
    entity.setAnsweredAt(Instant.now());
    entity.setResponseNote(approved ? null : blankToNull(answer.feedback()));
    entity.setSnoozedUntil(null);
    notifications.save(entity);

    audit.record(entity.getUserId(), ActorType.USER, null,
        approved ? "notification.permission_approved" : "notification.permission_denied",
        entity.getId().toString(), null);
    events.publishEvent(new PermissionAnswered(entity.getUserId(), entity.getId(), approved));

    // A permission request that halted a run releases it either way: approving lets the step run,
    // declining lets the run fail cleanly rather than hanging.
    if (entity.getRunId() != null) {
      var payload = Json.obj().put("approved", approved);
      if (entity.getResponseNote() != null) payload.put("feedback", entity.getResponseNote());
      if (approved && answer.inputs() != null && answer.inputs().isObject()
          && !answer.inputs().isEmpty()) {
        payload.set("inputs", answer.inputs());
      }
      resumeRun(entity, payload);
    }
    return toDto(entity);
  }

  /**
   * A device asked the person mid-command (a CAPTCHA, a code, an Ask step, a hand-over). Approving
   * goes on, with what they typed in {@code inputs.answer} unless they pressed Done
   * ({@code inputs.done}); declining stops the command. The answer is handed to the device module
   * in an event and kept nowhere: not on the item, not in the audit, not in the run.
   */
  private NotificationDto answerDeviceHelp(NotificationEntity entity, JsonNode help, Answer answer) {
    boolean approved = answer.kind() == Answer.Kind.APPROVE;
    JsonNode inputs = answer.inputs();
    String typed = null;
    if (approved && inputs != null && inputs.path("answer").isTextual()) {
      typed = inputs.get("answer").asText().strip();
      if (typed.isEmpty()) typed = null;
      else if (typed.length() > MAX_DEVICE_ANSWER) typed = typed.substring(0, MAX_DEVICE_ANSWER);
    }
    // A secret answer the card sealed to the device's key: checked for shape, relayed unread.
    JsonNode sealed = null;
    if (approved && inputs != null && inputs.has("sealed")) {
      if (!help.path("answer").path("secret").asBoolean(false)
          || !help.path("answer").hasNonNull("key")) {
        throw new ValidationException("This card does not take a sealed answer");
      }
      sealed = SealedAnswers.of(inputs.get("sealed"));
      if (sealed == null) throw new ValidationException("The sealed answer is not valid");
      typed = null;
    }
    boolean done = inputs != null && inputs.path("done").asBoolean(false);
    boolean required = help.path("answer").path("required").asBoolean(false);
    if (approved && required && typed == null && sealed == null && !done) {
      throw new ValidationException(
          "Type the answer, or press Done if you finished it in the browser");
    }
    UUID deviceId = uuidOrNull(help.path("deviceId").asText(null));
    UUID commandId = uuidOrNull(help.path("commandId").asText(null));
    if (deviceId == null || commandId == null) {
      throw new ValidationException("This item no longer reaches its device");
    }
    entity.setStatus(approved ? NotificationStatus.APPROVED : NotificationStatus.DENIED);
    entity.setAnsweredAt(Instant.now());
    entity.setResponseNote(approved ? null : "Stopped");
    entity.setSnoozedUntil(null);
    notifications.save(entity);
    audit.record(entity.getUserId(), ActorType.USER, null,
        approved ? "notification.device_help_answered" : "notification.device_help_stopped",
        entity.getId().toString(),
        Json.obj().put("commandId", commandId.toString())
            .put("answered", typed != null || sealed != null).put("sealed", sealed != null));
    events.publishEvent(new DeviceHelpAnswered(entity.getUserId(), entity.getId(), deviceId,
        commandId, !approved, typed, sealed));
    NotificationDto dto = toDto(entity);
    realtime.ifAvailable(gateway -> gateway.publish(entity.getUserId(), "notifications", dto));
    return dto;
  }

  private static UUID uuidOrNull(String value) {
    if (value == null) return null;
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  /**
   * A step's approval card can list mandatory values that resolved empty; approving needs each of
   * them filled in, so a run never goes on to fail for a value the person was asked for.
   */
  static void requireFilledIn(NotificationEntity entity, JsonNode inputs) {
    JsonNode form = entity.getContext() == null ? null : entity.getContext().path("form");
    if (form == null || !form.path("missing").isArray() || form.get("missing").isEmpty()) return;
    java.util.List<String> unfilled = new java.util.ArrayList<>();
    for (JsonNode key : form.get("missing")) {
      JsonNode value = inputs == null ? null : inputs.get(key.asText());
      boolean empty = value == null || value.isNull()
          || value.isTextual() && value.asText().isBlank()
          || value.isArray() && value.isEmpty();
      if (empty) unfilled.add(labelOf(form, key.asText()));
    }
    if (!unfilled.isEmpty()) {
      throw new ValidationException("Fill in " + String.join(", ", unfilled)
          + " before approving");
    }
  }

  private static String labelOf(JsonNode form, String key) {
    for (JsonNode field : form.path("fields")) {
      if (key.equals(field.path("key").asText())) return field.path("label").asText(key);
    }
    return key;
  }

  private NotificationDto answerReview(NotificationEntity entity, Answer answer) {
    ProcedureService service = procedures.getIfAvailable();
    if (service == null) {
      throw new IllegalStateException("Procedure review is unavailable in this configuration");
    }
    if (entity.getWorkflowId() == null) {
      throw new ValidationException("That review is not attached to a procedure");
    }

    boolean approved = answer.kind() == Answer.Kind.APPROVE;
    if (approved) {
      service.approve(entity.getWorkflowId());
    } else {
      service.reject(entity.getWorkflowId(), answer.feedback());
    }
    entity.setStatus(approved ? NotificationStatus.APPROVED : NotificationStatus.DENIED);
    entity.setAnsweredAt(Instant.now());
    entity.setResponseNote(approved ? null : blankToNull(answer.feedback()));
    entity.setSnoozedUntil(null);
    notifications.save(entity);

    audit.record(entity.getUserId(), ActorType.USER, null,
        approved ? "notification.review_approved" : "notification.review_rejected",
        entity.getWorkflowId().toString(),
        Json.obj().put("feedback", String.valueOf(answer.feedback())));
    events.publishEvent(new ReviewAnswered(entity.getUserId(), entity.getId(),
        entity.getWorkflowId(), approved, answer.feedback()));
    return toDto(entity);
  }

  private NotificationDto answerInstruction(NotificationEntity entity, Answer answer) {
    if (answer.kind() != Answer.Kind.SUBMIT_INSTRUCTION
        || answer.instruction() == null || answer.instruction().isBlank()) {
      throw new ValidationException("This item needs a written instruction to continue");
    }
    // A question about a run that already ended has nothing left to answer: say so on the item
    // and file it, rather than refuse the answer and leave it in the inbox for good.
    if (entity.getRunId() != null && runHasEnded(entity.getRunId())) {
      close(entity, RUN_ALREADY_ENDED);
      return toDto(entity);
    }
    entity.setStatus(NotificationStatus.SUBMITTED);
    entity.setSubmittedInstruction(answer.instruction());
    entity.setAnsweredAt(Instant.now());
    entity.setSnoozedUntil(null);
    notifications.save(entity);

    audit.record(entity.getUserId(), ActorType.USER, null, "notification.instruction_submitted",
        entity.getId().toString(), null);
    events.publishEvent(new InstructionSubmitted(entity.getUserId(), entity.getId(),
        entity.getRunId(), answer.instruction()));

    if (entity.getRunId() != null) {
      resumeRun(entity, Json.obj().put("instruction", answer.instruction()));
    }
    return toDto(entity);
  }

  /**
   * Hands the answer to the run once the answer itself is saved, in a transaction of its own.
   * Resuming inside the answer's transaction meant any failure while the run carried on (a run
   * that had ended, a step that threw) rolled the answer back too, so the item stayed in the inbox
   * however many times it was answered.
   */
  private void resumeRun(NotificationEntity entity, com.fasterxml.jackson.databind.JsonNode payload) {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
          inOwnTransaction(() -> resumeNow(entity, payload));
        }
      });
    } else {
      resumeNow(entity, payload);
    }
  }

  private void inOwnTransaction(Runnable work) {
    PlatformTransactionManager manager = transactionManager;
    if (manager == null) {
      work.run();
      return;
    }
    TransactionTemplate template = new TransactionTemplate(manager);
    template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    try {
      template.executeWithoutResult(status -> work.run());
    } catch (RuntimeException e) {
      log.warn("Could not resume run after answering a notification", e);
    }
  }

  private void resumeNow(NotificationEntity entity, com.fasterxml.jackson.databind.JsonNode payload) {
    WorkflowEngine engine = workflowEngine.getIfAvailable();
    if (engine == null) {
      log.warn("Notification {} answered but the workflow engine is unavailable, so run {} is "
          + "still paused", entity.getId(), entity.getRunId());
      return;
    }
    // Parallel paths can each be waiting on the person; the step this item is about rides along so
    // the answer resumes that one.
    if (payload instanceof com.fasterxml.jackson.databind.node.ObjectNode object
        && entity.getContext() != null && entity.getContext().path("node").isTextual()) {
      object.put("node", entity.getContext().get("node").asText());
    }
    try {
      engine.resume(entity.getRunId(), payload);
    } catch (RuntimeException e) {
      // The answer itself is recorded; a resume failure must not undo it.
      log.warn("Could not resume run {} after answering notification {}", entity.getRunId(),
          entity.getId(), e);
    }
  }

  /** What a failed step's question falls back to when the step did not say. */
  static final String DEFAULT_FAILED_STEP_CHOICE = "Stop the run";

  /**
   * The answer a question about a run gives itself when nobody answered in time, or null when it
   * has none and simply expires. A failed step's question names one ({@code defaultChoice}); one
   * raised before steps could set it is stopped, so the run does not wait on it for good.
   */
  public static String defaultAnswerOf(NotificationEntity item) {
    if (item.getKind() != NotificationKind.INSTRUCTION_REQUIRED || item.getRunId() == null) {
      return null;
    }
    var context = item.getContext();
    if (context != null && context.path("defaultChoice").isTextual()
        && !context.get("defaultChoice").asText().isBlank()) {
      return context.get("defaultChoice").asText();
    }
    boolean failedStep = (context != null && context.path("failedStep").asBoolean(false))
        || (item.getBody() != null && item.getBody().contains("could not complete:"));
    return failedStep ? DEFAULT_FAILED_STEP_CHOICE : null;
  }

  /**
   * Answers a question that expired unanswered with its default answer and hands that to the run,
   * as if the person had picked it. The item says so, so nobody thinks they answered it.
   *
   * @return false when the question has no default answer and should simply expire
   */
  @Transactional(propagation = Propagation.REQUIRED)
  public boolean answerByDefault(NotificationEntity item) {
    String answer = defaultAnswerOf(item);
    if (answer == null) return false;
    if (runHasEnded(item.getRunId())) {
      close(item, RUN_ALREADY_ENDED);
      return true;
    }
    item.setStatus(NotificationStatus.SUBMITTED);
    item.setSubmittedInstruction(answer);
    item.setResponseNote("No answer in time, so Jizza chose the default");
    item.setEscalationState(EscalationState.AUTO_CANCELLED);
    item.setAnsweredAt(Instant.now());
    item.setSnoozedUntil(null);
    var context = item.getContext() instanceof com.fasterxml.jackson.databind.node.ObjectNode o
        ? o.deepCopy() : Json.obj();
    context.put("answeredByDefault", true);
    item.setContext(context);
    notifications.save(item);

    audit.record(item.getUserId(), ActorType.SYSTEM, item.getSourceAgentId(),
        "notification.default_answer", item.getId().toString(), Json.obj().put("answer", answer));
    events.publishEvent(new InstructionSubmitted(item.getUserId(), item.getId(),
        item.getRunId(), answer));
    NotificationDto dto = toDto(item);
    realtime.ifAvailable(gateway -> gateway.publish(item.getUserId(), "notifications", dto));
    resumeRun(item, Json.obj().put("instruction", answer));
    return true;
  }

  static final String RUN_ALREADY_ENDED = "Closed: the run had already ended";

  private boolean runHasEnded(UUID runId) {
    WorkflowEngine engine = workflowEngine.getIfAvailable();
    if (engine == null) return false;
    try {
      var run = engine.get(runId);
      if (run == null || run.status() == null) return false;
      RunStatus status = run.status();
      return status == RunStatus.DONE || status == RunStatus.FAILED
          || status == RunStatus.CANCELLED;
    } catch (RuntimeException e) {
      // A run that is gone has ended as far as this item is concerned.
      return e instanceof NotFoundException;
    }
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRED)
  public int closeForRun(UUID runId, String nodeId, String reason) {
    if (runId == null) return 0;
    int closed = 0;
    for (NotificationEntity item : notifications.findByRunId(runId)) {
      boolean open = item.getStatus() == NotificationStatus.UNREAD
          || item.getStatus() == NotificationStatus.READ;
      if (!open || !item.getKind().blocking()) continue;
      if (nodeId != null && (item.getContext() == null
          || !nodeId.equals(item.getContext().path("node").asText(null)))) {
        continue;
      }
      close(item, reason);
      closed++;
    }
    return closed;
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRED)
  public boolean closeOpen(UUID notificationId, String reason) {
    NotificationEntity item = notifications.findById(notificationId).orElse(null);
    if (item == null) return false;
    boolean open = item.getStatus() == NotificationStatus.UNREAD
        || item.getStatus() == NotificationStatus.READ;
    if (!open || !item.getKind().blocking()) return false;
    close(item, reason);
    return true;
  }

  /** Files an item that no longer needs an answer, saying why on the item itself. */
  private void close(NotificationEntity item, String reason) {
    item.setStatus(NotificationStatus.EXPIRED);
    item.setEscalationState(EscalationState.AUTO_CANCELLED);
    item.setResponseNote(blankToNull(reason));
    item.setAnsweredAt(Instant.now());
    item.setSnoozedUntil(null);
    notifications.save(item);
    audit.record(item.getUserId(), ActorType.SYSTEM, item.getSourceAgentId(),
        "notification.closed", item.getId().toString(),
        Json.obj().put("reason", String.valueOf(reason)));
    NotificationDto dto = toDto(item);
    realtime.ifAvailable(gateway -> gateway.publish(item.getUserId(), "notifications", dto));
  }

  @Override
  @Transactional(readOnly = true)
  public List<NotificationDto> list(UUID userId, boolean unreadOnly) {
    List<NotificationEntity> rows = unreadOnly
        ? notifications.findByUserIdAndStatusOrderByCreatedAtDesc(userId, NotificationStatus.UNREAD)
        : notifications.findByUserIdOrderByCreatedAtDesc(userId);
    return rows.stream().map(this::toDto).toList();
  }

  @Override
  @Transactional(readOnly = true)
  public boolean raisedSince(UUID userId, UUID sourceAgentId, NotificationKind kind, String body,
                             Instant since) {
    return notifications.existsByUserIdAndSourceAgentIdAndKindAndBodyAndCreatedAtAfter(
        userId, sourceAgentId, kind, body, since);
  }

  /** Raised in the transaction that created an urgent item; delivered once that commits. */
  public record UrgentNotificationRaised(UUID notificationId) {}

  @Override
  @Transactional(readOnly = true)
  public NotificationDto get(UUID notificationId) {
    return toDto(require(notificationId));
  }

  @Override
  @Transactional
  public void markRead(UUID notificationId) {
    NotificationEntity entity = require(notificationId);
    if (entity.getStatus() == NotificationStatus.UNREAD) {
      entity.setStatus(NotificationStatus.READ);
      notifications.save(entity);
    }
  }

  @Override
  @Transactional
  public void markUnread(UUID notificationId) {
    NotificationEntity entity = require(notificationId);
    if (entity.getStatus() == NotificationStatus.READ) {
      entity.setStatus(NotificationStatus.UNREAD);
      notifications.save(entity);
    }
  }

  @Override
  @Transactional
  public int markAllRead(UUID userId) {
    List<NotificationEntity> unread =
        notifications.findByUserIdAndStatusOrderByCreatedAtDesc(userId, NotificationStatus.UNREAD);
    unread.forEach(n -> n.setStatus(NotificationStatus.READ));
    notifications.saveAll(unread);
    return unread.size();
  }

  @Override
  @Transactional
  public NotificationDto archive(UUID notificationId, boolean archived) {
    NotificationEntity entity = require(notificationId);
    if (archived && isOpen(entity)) {
      throw new ValidationException(
          "This is still waiting on you. Approve or decline it before filing it away.");
    }
    entity.setArchivedAt(archived ? Instant.now() : null);
    if (archived) {
      entity.setSnoozedUntil(null);
      if (entity.getStatus() == NotificationStatus.UNREAD) entity.setStatus(NotificationStatus.READ);
    }
    notifications.save(entity);
    return toDto(entity);
  }

  @Override
  @Transactional
  public NotificationDto snooze(UUID notificationId, Instant until) {
    NotificationEntity entity = require(notificationId);
    if (until != null) {
      if (!until.isAfter(Instant.now())) {
        throw new ValidationException("Pick a time in the future to snooze until");
      }
      if (entity.getKind().blocking() && !isOpen(entity)) {
        throw new ValidationException("That item has already been dealt with");
      }
      // A request that expires while hidden would be lost: snooze no longer than it can wait.
      if (isOpen(entity) && entity.getExpiresAt() != null && until.isAfter(entity.getExpiresAt())) {
        throw new ValidationException("This request expires before then. Snooze it for less time,"
            + " or answer it now.");
      }
    }
    entity.setSnoozedUntil(until);
    notifications.save(entity);
    return toDto(entity);
  }

  private static boolean isOpen(NotificationEntity n) {
    return n.getKind().blocking()
        && (n.getStatus() == NotificationStatus.UNREAD || n.getStatus() == NotificationStatus.READ);
  }

  private static String blankToNull(String text) {
    return text == null || text.isBlank() ? null : text.strip();
  }

  /**
   * Blocks an unattended caller until a permission request is answered. Polls rather than waits on
   * a monitor so the answer can arrive on any process, not only this one.
   */
  @Override
  public boolean awaitPermission(UUID notificationId, Duration timeout) {
    Instant deadline = Instant.now().plus(timeout);
    while (Instant.now().isBefore(deadline)) {
      NotificationStatus status = notifications.findById(notificationId)
          .map(NotificationEntity::getStatus).orElse(NotificationStatus.EXPIRED);
      if (status == NotificationStatus.APPROVED) return true;
      if (status == NotificationStatus.DENIED || status == NotificationStatus.EXPIRED) return false;
      try {
        Thread.sleep(props.getPermissionPollInterval().toMillis());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      }
    }
    return false;
  }

  private void publishRaised(NotificationKind kind, NotificationDto dto) {
    switch (kind) {
      case INFO -> events.publishEvent(new InfoRaised(dto.userId(), dto.id(), dto.sourceAgentId(),
          dto.sourceAgentName(), dto.body()));
      case PERMISSION_REQUEST -> events.publishEvent(new PermissionRequested(dto.userId(), dto.id(),
          dto.sourceAgentId(), dto.sourceAgentName(), dto.body(),
          dto.context() == null ? null : dto.context().path("tool").asText(null)));
      case WORKFLOW_REVIEW -> events.publishEvent(new WorkflowReviewRequested(dto.userId(), dto.id(),
          dto.sourceAgentId(), dto.sourceAgentName(), dto.workflowId(),
          dto.context() == null ? 1 : dto.context().path("version").asInt(1)));
      case INSTRUCTION_REQUIRED -> events.publishEvent(new InstructionRequired(dto.userId(),
          dto.id(), dto.sourceAgentId(), dto.sourceAgentName(), dto.runId(), dto.body()));
    }
  }

  private NotificationEntity require(UUID notificationId) {
    return notifications.findById(notificationId)
        .orElseThrow(() -> new NotFoundException("Notification", notificationId));
  }

  NotificationDto toDto(NotificationEntity n) {
    String agentName = n.getSourceAgentId() == null ? "Jizza" : agents.nameOf(n.getSourceAgentId());
    return new NotificationDto(n.getId(), n.getUserId(), n.getSourceAgentId(), agentName,
        n.getKind(), n.getBody(), n.getContext(), n.getTaskId(), n.getWorkflowId(), n.getRunId(),
        n.getStatus(), n.getSubmittedInstruction(), n.getExpiresAt(), n.getEscalationState(),
        n.getCreatedAt(), n.getAnsweredAt(),
        n.getPriority() == null ? NotificationPriority.NORMAL : n.getPriority(),
        n.getArchivedAt(), n.getSnoozedUntil(), n.getResponseNote());
  }
}
