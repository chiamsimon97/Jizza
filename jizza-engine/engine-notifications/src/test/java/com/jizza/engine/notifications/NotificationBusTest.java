package com.jizza.engine.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.dto.AgentDto;
import com.jizza.engine.api.enums.EscalationState;
import com.jizza.engine.api.enums.NotificationKind;
import com.jizza.engine.api.enums.NotificationStatus;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.mail.MailSender;
import com.jizza.engine.api.notification.Answer;
import com.jizza.engine.api.notification.NotificationContext;
import com.jizza.engine.api.orchestrator.AgentDirectory;
import com.jizza.engine.api.procedure.ProcedureService;
import com.jizza.engine.api.realtime.RealtimeGateway;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.workflow.WorkflowEngine;
import com.jizza.engine.notifications.bus.DefaultNotificationBus;
import com.jizza.engine.notifications.config.NotificationProperties;
import com.jizza.engine.notifications.escalation.EscalationSweeper;
import com.jizza.engine.persistence.entity.NotificationEntity;
import com.jizza.engine.persistence.entity.UserEntity;
import com.jizza.engine.persistence.repository.NotificationRepository;
import com.jizza.engine.persistence.repository.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;

class NotificationBusTest {

  private final UUID userId = UUID.randomUUID();
  private final UUID agentId = UUID.randomUUID();
  private final List<NotificationEntity> rows = new ArrayList<>();

  private NotificationRepository notifications;
  private WorkflowEngine engine;
  private ProcedureService procedures;
  private RealtimeGateway realtime;
  private MailSender mail;
  private ApplicationEventPublisher events;
  private UserRepository users;
  private NotificationProperties props;
  private DefaultNotificationBus bus;
  private EscalationSweeper sweeper;

  @SuppressWarnings("unchecked")
  private <T> ObjectProvider<T> provider(T value) {
    ObjectProvider<T> op = mock(ObjectProvider.class);
    when(op.getIfAvailable()).thenReturn(value);
    org.mockito.Mockito.doAnswer(inv -> {
      if (value != null) ((java.util.function.Consumer<T>) inv.getArgument(0)).accept(value);
      return null;
    }).when(op).ifAvailable(any());
    return op;
  }

  @BeforeEach
  void setUp() {
    notifications = mock(NotificationRepository.class);
    when(notifications.save(any())).thenAnswer(inv -> {
      NotificationEntity n = inv.getArgument(0);
      if (n.getId() == null) {
        n.setId(UUID.randomUUID());
        n.setCreatedAt(Instant.now());
      }
      rows.removeIf(r -> r.getId().equals(n.getId()));
      rows.add(n);
      return n;
    });
    when(notifications.findById(any())).thenAnswer(inv ->
        rows.stream().filter(r -> r.getId().equals(inv.getArgument(0))).findFirst());
    when(notifications.findByUserIdOrderByCreatedAtDesc(any())).thenAnswer(inv ->
        rows.stream().filter(r -> r.getUserId().equals(inv.<UUID>getArgument(0))).toList());
    when(notifications.findOpenBlockingDueBy(any())).thenAnswer(inv -> {
      Instant horizon = inv.getArgument(0);
      return rows.stream()
          .filter(r -> r.getKind().blocking())
          .filter(r -> r.getStatus() == NotificationStatus.UNREAD
              || r.getStatus() == NotificationStatus.READ)
          .filter(r -> r.getExpiresAt() != null && !r.getExpiresAt().isAfter(horizon))
          .toList();
    });

    AgentDirectory agents = mock(AgentDirectory.class);
    when(agents.nameOf(agentId)).thenReturn("Kai");
    when(agents.byId(agentId)).thenReturn(Optional.of(
        new AgentDto(agentId, userId, "finance", "Kai", "finance", "p", false, true)));

    engine = mock(WorkflowEngine.class);
    procedures = mock(ProcedureService.class);
    realtime = mock(RealtimeGateway.class);
    mail = mock(MailSender.class);
    events = mock(ApplicationEventPublisher.class);

    users = mock(UserRepository.class);
    UserEntity user = new UserEntity();
    user.setId(userId);
    user.setEmail("owner@jizza.local");
    when(users.findById(userId)).thenReturn(Optional.of(user));

    props = new NotificationProperties();
    AuditLog audit = mock(AuditLog.class);

    bus = new DefaultNotificationBus(notifications, agents, provider(engine), provider(procedures),
        provider(realtime), events, audit, props);
    sweeper = new EscalationSweeper(notifications, users, agents, provider(mail), provider(realtime),
        audit, props);
  }

  @Test
  void anInfoItemIsDeliveredAttributedToTheAgentByName() {
    var raised = bus.raise(NotificationKind.INFO, agentId,
        NotificationContext.info(userId, "Your card statement is ready."));

    assertThat(raised.kind()).isEqualTo(NotificationKind.INFO);
    assertThat(raised.sourceAgentName()).isEqualTo("Kai");
    assertThat(raised.status()).isEqualTo(NotificationStatus.UNREAD);
    assertThat(raised.expiresAt()).as("nothing is blocked, so nothing expires").isNull();
    verify(realtime).publish(eq(userId), eq("notifications"), any());
  }

  @Test
  void everyBlockingKindGetsAnExpiryAppropriateToIt() {
    var permission = bus.raise(NotificationKind.PERMISSION_REQUEST, agentId,
        NotificationContext.info(userId, "approve?"));
    var review = bus.raise(NotificationKind.WORKFLOW_REVIEW, agentId,
        NotificationContext.info(userId, "review?"));
    var instruction = bus.raise(NotificationKind.INSTRUCTION_REQUIRED, agentId,
        NotificationContext.info(userId, "which one?"));

    assertThat(permission.expiresAt()).isNotNull();
    assertThat(review.expiresAt()).isAfter(permission.expiresAt());
    assertThat(instruction.expiresAt()).isNotNull();
  }

  @Test
  void approvingAPermissionReleasesThePausedRun() {
    UUID runId = UUID.randomUUID();
    var raised = bus.raise(NotificationKind.PERMISSION_REQUEST, agentId,
        NotificationContext.forRun(userId, "Send the invoice?", null, runId));

    var answered = bus.answer(raised.id(), Answer.approve());

    assertThat(answered.status()).isEqualTo(NotificationStatus.APPROVED);
    var payload = org.mockito.ArgumentCaptor.forClass(com.fasterxml.jackson.databind.JsonNode.class);
    verify(engine).resume(eq(runId), payload.capture());
    assertThat(payload.getValue().path("approved").asBoolean()).isTrue();
  }

  @Test
  void denyingAPermissionAlsoReleasesTheRunRatherThanLeavingItStuck() {
    UUID runId = UUID.randomUUID();
    var raised = bus.raise(NotificationKind.PERMISSION_REQUEST, agentId,
        NotificationContext.forRun(userId, "Send the invoice?", null, runId));

    var answered = bus.answer(raised.id(), Answer.deny("not this one"));

    assertThat(answered.status()).isEqualTo(NotificationStatus.DENIED);
    var payload = org.mockito.ArgumentCaptor.forClass(com.fasterxml.jackson.databind.JsonNode.class);
    verify(engine).resume(eq(runId), payload.capture());
    assertThat(payload.getValue().path("approved").asBoolean()).isFalse();
  }

  @Test
  void approvingAReviewApprovesTheProcedureAndRejectingReturnsTheFeedback() {
    UUID workflowId = UUID.randomUUID();
    var approve = bus.raise(NotificationKind.WORKFLOW_REVIEW, agentId,
        NotificationContext.forWorkflow(userId, "review this", null, workflowId, null));
    bus.answer(approve.id(), Answer.approve());
    verify(procedures).approve(workflowId);

    var reject = bus.raise(NotificationKind.WORKFLOW_REVIEW, agentId,
        NotificationContext.forWorkflow(userId, "review this too", null, workflowId, null));
    bus.answer(reject.id(), Answer.deny("too risky"));
    verify(procedures).reject(workflowId, "too risky");
  }

  @Test
  void submittingAnInstructionResumesTheRunWithTheUsersOwnWords() {
    UUID runId = UUID.randomUUID();
    var raised = bus.raise(NotificationKind.INSTRUCTION_REQUIRED, agentId,
        NotificationContext.forRun(userId, "Which supplier?", null, runId));

    var answered = bus.answer(raised.id(), Answer.instruction("Use Acme Ltd"));

    assertThat(answered.status()).isEqualTo(NotificationStatus.SUBMITTED);
    assertThat(answered.submittedInstruction()).isEqualTo("Use Acme Ltd");
    var payload = org.mockito.ArgumentCaptor.forClass(com.fasterxml.jackson.databind.JsonNode.class);
    verify(engine).resume(eq(runId), payload.capture());
    assertThat(payload.getValue().path("instruction").asText()).isEqualTo("Use Acme Ltd");
  }

  @Test
  void anInterventionNeedsActualWordsNotJustAnApproval() {
    var raised = bus.raise(NotificationKind.INSTRUCTION_REQUIRED, agentId,
        NotificationContext.forRun(userId, "Which supplier?", null, UUID.randomUUID()));

    assertThatThrownBy(() -> bus.answer(raised.id(), Answer.approve()))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("written instruction");
    assertThatThrownBy(() -> bus.answer(raised.id(), Answer.instruction("   ")))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void anItemCannotBeAnsweredTwice() {
    var raised = bus.raise(NotificationKind.PERMISSION_REQUEST, agentId,
        NotificationContext.info(userId, "approve?"));
    bus.answer(raised.id(), Answer.approve());

    assertThatThrownBy(() -> bus.answer(raised.id(), Answer.deny(null)))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("already been answered");
  }

  @Test
  void anInformationalItemHasNothingToAnswer() {
    var raised = bus.raise(NotificationKind.INFO, agentId,
        NotificationContext.info(userId, "just so you know"));

    assertThatThrownBy(() -> bus.answer(raised.id(), Answer.approve()))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void aFailingResumeDoesNotUndoTheRecordedAnswer() {
    UUID runId = UUID.randomUUID();
    org.mockito.Mockito.doThrow(new IllegalStateException("engine unavailable"))
        .when(engine).resume(any(), any());
    var raised = bus.raise(NotificationKind.INSTRUCTION_REQUIRED, agentId,
        NotificationContext.forRun(userId, "Which supplier?", null, runId));

    var answered = bus.answer(raised.id(), Answer.instruction("Use Acme Ltd"));

    assertThat(answered.status()).isEqualTo(NotificationStatus.SUBMITTED);
  }

  // ── expiry and escalation ────────────────────────────────────────────────

  @Test
  void anItemNearingExpiryIsChasedByEmailWithoutLeakingItsDetail() {
    var raised = bus.raise(NotificationKind.PERMISSION_REQUEST, agentId,
        new NotificationContext(userId, "Pay the 42,000 rupee invoice to Acme?", null, null, null,
            null, Duration.ofMinutes(30)));

    sweeper.sweep();

    var body = org.mockito.ArgumentCaptor.forClass(String.class);
    verify(mail).send(eq("owner@jizza.local"), eq("Jizza is waiting on you"), body.capture());
    assertThat(body.getValue()).contains("Kai").doesNotContain("42,000").doesNotContain("Acme");

    var stored = rows.stream().filter(r -> r.getId().equals(raised.id())).findFirst().orElseThrow();
    assertThat(stored.getEscalationState()).isEqualTo(EscalationState.ESCALATED);
    assertThat(stored.getReminderCount()).isEqualTo(1);
  }

  @Test
  void anItemIsNotChasedAgainInsideItsCooldown() {
    bus.raise(NotificationKind.PERMISSION_REQUEST, agentId,
        new NotificationContext(userId, "approve?", null, null, null, null, Duration.ofMinutes(30)));

    sweeper.sweep();
    sweeper.sweep();

    verify(mail, org.mockito.Mockito.times(1)).send(any(), any(), any());
  }

  @Test
  void chasingStopsAfterTheConfiguredNumberOfReminders() {
    props.setReminderCooldown(Duration.ZERO);
    props.setMaxReminders(2);
    bus.raise(NotificationKind.PERMISSION_REQUEST, agentId,
        new NotificationContext(userId, "approve?", null, null, null, null, Duration.ofMinutes(30)));

    for (int i = 0; i < 5; i++) sweeper.sweep();

    verify(mail, org.mockito.Mockito.times(2)).send(any(), any(), any());
  }

  @Test
  void anUnansweredItemExpiresSoNothingHangsUnseen() {
    var raised = bus.raise(NotificationKind.PERMISSION_REQUEST, agentId,
        NotificationContext.info(userId, "approve?"));
    expireNow(raised.id());

    sweeper.sweep();

    var stored = rows.stream().filter(r -> r.getId().equals(raised.id())).findFirst().orElseThrow();
    assertThat(stored.getStatus()).isEqualTo(NotificationStatus.EXPIRED);
    assertThat(stored.getEscalationState()).isEqualTo(EscalationState.AUTO_CANCELLED);
  }

  @Test
  void anExpiredItemCanNoLongerBeAnswered() {
    var raised = bus.raise(NotificationKind.PERMISSION_REQUEST, agentId,
        NotificationContext.info(userId, "approve?"));
    expireNow(raised.id());
    sweeper.sweep();

    assertThatThrownBy(() -> bus.answer(raised.id(), Answer.approve()))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("expired");
  }

  @Test
  void anAnsweredItemIsNoLongerChased() {
    var raised = bus.raise(NotificationKind.PERMISSION_REQUEST, agentId,
        new NotificationContext(userId, "approve?", null, null, null, null, Duration.ofMinutes(30)));
    bus.answer(raised.id(), Answer.approve());

    sweeper.sweep();

    verify(mail, never()).send(any(), any(), any());
  }

  /** Winds an item's expiry into the past, which is what the sweeper actually reacts to. */
  private void expireNow(UUID notificationId) {
    rows.stream().filter(r -> r.getId().equals(notificationId)).findFirst()
        .orElseThrow().setExpiresAt(Instant.now().minusSeconds(1));
  }

  @Test
  void anInfoItemIsNeverChasedOrExpired() {
    bus.raise(NotificationKind.INFO, agentId, NotificationContext.info(userId, "fyi"));
    sweeper.sweep();
    verify(mail, never()).send(any(), any(), any());
    assertThat(rows.get(0).getStatus()).isEqualTo(NotificationStatus.UNREAD);
  }

  @Test
  void awaitingAPermissionReturnsWhatTheUserDecided() {
    props.setPermissionPollInterval(Duration.ofMillis(5));
    var raised = bus.raise(NotificationKind.PERMISSION_REQUEST, agentId,
        NotificationContext.info(userId, "approve?"));
    bus.answer(raised.id(), Answer.approve());

    assertThat(bus.awaitPermission(raised.id(), Duration.ofMillis(200))).isTrue();
  }

  @Test
  void awaitingAnUnansweredPermissionGivesUpRatherThanBlockingForever() {
    props.setPermissionPollInterval(Duration.ofMillis(5));
    var raised = bus.raise(NotificationKind.PERMISSION_REQUEST, agentId,
        NotificationContext.info(userId, "approve?"));

    assertThat(bus.awaitPermission(raised.id(), Duration.ofMillis(50))).isFalse();
  }

  @Test
  void markingReadDoesNotCountAsAnswering() {
    var raised = bus.raise(NotificationKind.PERMISSION_REQUEST, agentId,
        NotificationContext.info(userId, "approve?"));

    bus.markRead(raised.id());

    assertThat(bus.get(raised.id()).status()).isEqualTo(NotificationStatus.READ);
    assertThat(bus.answer(raised.id(), Answer.approve()).status())
        .isEqualTo(NotificationStatus.APPROVED);
  }

  @Test
  void aDeclineReasonIsKeptAndHandedToThePausedRun() {
    UUID runId = UUID.randomUUID();
    var raised = bus.raise(NotificationKind.PERMISSION_REQUEST, agentId,
        NotificationContext.forRun(userId, "Send the invoice?", null, runId));

    var answered = bus.answer(raised.id(), Answer.deny("  wrong amount "));

    assertThat(answered.responseNote()).isEqualTo("wrong amount");
    var payload = org.mockito.ArgumentCaptor.forClass(com.fasterxml.jackson.databind.JsonNode.class);
    verify(engine).resume(eq(runId), payload.capture());
    assertThat(payload.getValue().path("feedback").asText()).isEqualTo("wrong amount");
  }

  @Test
  void somethingStillWaitingOnTheUserCannotBeFiledAwayButAnAnsweredOneCan() {
    var raised = bus.raise(NotificationKind.PERMISSION_REQUEST, agentId,
        NotificationContext.info(userId, "approve?"));

    assertThatThrownBy(() -> bus.archive(raised.id(), true))
        .isInstanceOf(ValidationException.class);

    bus.answer(raised.id(), Answer.deny(null));
    var filed = bus.archive(raised.id(), true);
    assertThat(filed.archivedAt()).isNotNull();
    assertThat(bus.archive(raised.id(), false).archivedAt()).isNull();
  }

  @Test
  void filingAnUpdateAwayAlsoMarksItRead() {
    var raised = bus.raise(NotificationKind.INFO, agentId, NotificationContext.info(userId, "fyi"));

    var filed = bus.archive(raised.id(), true);

    assertThat(filed.status()).isEqualTo(NotificationStatus.READ);
  }

  @Test
  void aRequestCannotBeSnoozedPastItsExpiry() {
    var raised = bus.raise(NotificationKind.PERMISSION_REQUEST, agentId,
        new NotificationContext(userId, "approve?", null, null, null, null, Duration.ofHours(2)));

    assertThatThrownBy(() -> bus.snooze(raised.id(), Instant.now().plus(Duration.ofHours(3))))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> bus.snooze(raised.id(), Instant.now().minusSeconds(5)))
        .isInstanceOf(ValidationException.class);

    var snoozed = bus.snooze(raised.id(), Instant.now().plus(Duration.ofHours(1)));
    assertThat(snoozed.snoozedUntil()).isNotNull();
    assertThat(bus.snooze(raised.id(), null).snoozedUntil()).isNull();
  }

  @Test
  void answeringClearsASnooze() {
    var raised = bus.raise(NotificationKind.PERMISSION_REQUEST, agentId,
        NotificationContext.info(userId, "approve?"));
    bus.snooze(raised.id(), Instant.now().plus(Duration.ofMinutes(30)));

    assertThat(bus.answer(raised.id(), Answer.approve()).snoozedUntil()).isNull();
  }

  @Test
  void readItemsCanBeMarkedUnreadAgainAndAllMarkedReadAtOnce() {
    when(notifications.findByUserIdAndStatusOrderByCreatedAtDesc(any(), any())).thenAnswer(inv ->
        rows.stream().filter(r -> r.getUserId().equals(inv.<UUID>getArgument(0))
            && r.getStatus() == inv.<NotificationStatus>getArgument(1)).toList());
    var first = bus.raise(NotificationKind.INFO, agentId, NotificationContext.info(userId, "one"));
    bus.raise(NotificationKind.INFO, agentId, NotificationContext.info(userId, "two"));

    assertThat(bus.markAllRead(userId)).isEqualTo(2);
    assertThat(bus.get(first.id()).status()).isEqualTo(NotificationStatus.READ);

    bus.markUnread(first.id());
    assertThat(bus.get(first.id()).status()).isEqualTo(NotificationStatus.UNREAD);
  }

  @Test
  void aSnoozedItemIsNotChasedUnlessUrgent() {
    var raised = bus.raise(NotificationKind.PERMISSION_REQUEST, agentId,
        new NotificationContext(userId, "approve?", null, null, null, null, Duration.ofMinutes(20)));
    bus.snooze(raised.id(), Instant.now().plus(Duration.ofMinutes(10)));

    sweeper.sweep();

    assertThat(bus.get(raised.id()).escalationState()).isEqualTo(EscalationState.NONE);
  }
}
