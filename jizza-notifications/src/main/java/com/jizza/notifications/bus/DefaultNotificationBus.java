package com.jizza.notifications.bus;

import com.jizza.common.audit.AuditLog;
import com.jizza.common.dto.NotificationDto;
import com.jizza.common.enums.ActorType;
import com.jizza.common.enums.EscalationState;
import com.jizza.common.enums.NotificationKind;
import com.jizza.common.enums.NotificationStatus;
import com.jizza.common.error.NotFoundException;
import com.jizza.common.error.ValidationException;
import com.jizza.common.event.InfoRaised;
import com.jizza.common.event.InstructionRequired;
import com.jizza.common.event.InstructionSubmitted;
import com.jizza.common.event.PermissionAnswered;
import com.jizza.common.event.PermissionRequested;
import com.jizza.common.event.ReviewAnswered;
import com.jizza.common.event.WorkflowReviewRequested;
import com.jizza.common.notification.Answer;
import com.jizza.common.notification.NotificationBus;
import com.jizza.common.notification.NotificationContext;
import com.jizza.common.orchestrator.AgentDirectory;
import com.jizza.common.procedure.ProcedureService;
import com.jizza.common.realtime.RealtimeGateway;
import com.jizza.common.util.Json;
import com.jizza.common.workflow.WorkflowEngine;
import com.jizza.notifications.config.NotificationProperties;
import com.jizza.persistence.entity.NotificationEntity;
import com.jizza.persistence.repository.NotificationRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
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

    return switch (entity.getKind()) {
      case PERMISSION_REQUEST -> answerPermission(entity, answer);
      case WORKFLOW_REVIEW -> answerReview(entity, answer);
      case INSTRUCTION_REQUIRED -> answerInstruction(entity, answer);
      case INFO -> throw new ValidationException("An informational item has nothing to answer");
    };
  }

  private NotificationDto answerPermission(NotificationEntity entity, Answer answer) {
    boolean approved = answer.kind() == Answer.Kind.APPROVE;
    entity.setStatus(approved ? NotificationStatus.APPROVED : NotificationStatus.DENIED);
    entity.setAnsweredAt(Instant.now());
    notifications.save(entity);

    audit.record(entity.getUserId(), ActorType.USER, null,
        approved ? "notification.permission_approved" : "notification.permission_denied",
        entity.getId().toString(), null);
    events.publishEvent(new PermissionAnswered(entity.getUserId(), entity.getId(), approved));

    // A permission request that halted a run releases it either way: approving lets the step run,
    // declining lets the run fail cleanly rather than hanging.
    if (entity.getRunId() != null) {
      resumeRun(entity, Json.obj().put("approved", approved));
    }
    return toDto(entity);
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
    entity.setStatus(NotificationStatus.SUBMITTED);
    entity.setSubmittedInstruction(answer.instruction());
    entity.setAnsweredAt(Instant.now());
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

  private void resumeRun(NotificationEntity entity, com.fasterxml.jackson.databind.JsonNode payload) {
    WorkflowEngine engine = workflowEngine.getIfAvailable();
    if (engine == null) {
      log.warn("Notification {} answered but the workflow engine is unavailable, so run {} is "
          + "still paused", entity.getId(), entity.getRunId());
      return;
    }
    try {
      engine.resume(entity.getRunId(), payload);
    } catch (RuntimeException e) {
      // The answer itself is recorded; a resume failure must not undo it.
      log.warn("Could not resume run {} after answering notification {}", entity.getRunId(),
          entity.getId(), e);
    }
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
        n.getCreatedAt(), n.getAnsweredAt());
  }
}
