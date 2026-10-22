package com.jizza.engine.workflow.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.dto.RunDto;
import com.jizza.engine.api.enums.RunStatus;
import com.jizza.engine.api.enums.WorkflowOrigin;
import com.jizza.engine.api.enums.WorkflowStatus;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.workflow.WorkflowEngine;
import com.jizza.engine.api.workflow.library.ReuseProfile;
import com.jizza.engine.api.workflow.library.WorkflowLibrary.Use;
import com.jizza.engine.persistence.entity.WorkflowEntity;
import com.jizza.engine.persistence.repository.WorkflowRepository;
import com.jizza.engine.persistence.repository.WorkflowRunRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DefaultWorkflowLibraryTest {

  private final UUID userId = UUID.randomUUID();
  private WorkflowRepository workflows;
  private WorkflowRunRepository runs;
  private WorkflowEngine engine;
  private DefaultWorkflowLibrary library;
  private WorkflowEntity gold;

  @BeforeEach
  void setUp() {
    workflows = mock(WorkflowRepository.class);
    runs = mock(WorkflowRunRepository.class);
    engine = mock(WorkflowEngine.class);
    library = new DefaultWorkflowLibrary(workflows, runs, engine, mock(AuditLog.class),
        Clock.fixed(Instant.parse("2026-10-22T10:15:00Z"), ZoneOffset.UTC));

    gold = workflow("Gold rate checker", WorkflowStatus.APPROVED, profile(
        new ReuseProfile.Guardrails(true, false, null, List.of("Only check, never buy"), null)));
    when(workflows.findById(any())).thenAnswer(inv -> Optional.of(gold)
        .filter(w -> w.getId().equals(inv.getArgument(0))));
    when(workflows.findByUserIdOrderByCreatedAtDesc(userId)).thenAnswer(inv -> List.of(gold));
    when(engine.start(any(), anyInt(), any(), anyString())).thenAnswer(inv -> new RunDto(
        UUID.randomUUID(), userId, inv.getArgument(0), inv.getArgument(1), inv.getArgument(3),
        RunStatus.DONE, null, Json.obj(), Instant.now(), Instant.now(), List.of()));
  }

  @Test
  void findsTheSavedWorkflowForARequestLikeItsExamples() {
    var found = library.find(userId, "check the gold rate please", Use.CHAT, 3);

    assertThat(found).singleElement().satisfies(m -> {
      assertThat(m.workflowId()).isEqualTo(gold.getId());
      assertThat(m.profile().guardrails().rules()).containsExactly("Only check, never buy");
    });
  }

  @Test
  void aWorkflowNotSavedForReuseIsNeverOffered() {
    gold.setReuse(null);

    assertThat(library.find(userId, "check the gold rate", Use.CHAT, 3)).isEmpty();
    assertThatThrownBy(() -> library.reuse(userId, gold.getId(), Json.obj(), Use.CHAT, "chat:x", true))
        .isInstanceOf(ValidationException.class).hasMessageContaining("not saved for reuse");
  }

  @Test
  void oneAwaitingReviewOrTurnedOffIsLeftOut() {
    gold.setStatus(WorkflowStatus.PENDING_REVIEW);
    assertThat(library.find(userId, "check the gold rate", Use.CHAT, 3)).isEmpty();

    gold.setStatus(WorkflowStatus.APPROVED);
    gold.setDisabled(true);
    assertThat(library.find(userId, "check the gold rate", Use.CHAT, 3)).isEmpty();
  }

  @Test
  void aChatRunWaitsForTheUserWhenItsGuardrailsSayToAsk() {
    assertThatThrownBy(() -> library.reuse(userId, gold.getId(), Json.obj(), Use.CHAT, "chat:x", false))
        .isInstanceOf(ValidationException.class).hasMessageContaining("once the user says so");
    verify(engine, never()).start(any(), anyInt(), any(), anyString());

    var run = library.reuse(userId, gold.getId(), Json.obj(), Use.CHAT, "chat:x", true);

    assertThat(run.triggerSource()).isEqualTo("manual:library:chat:x");
  }

  @Test
  void aDraftAlwaysAsksEvenWhenTheUserSaidItNeedNot() {
    gold.setStatus(WorkflowStatus.DRAFT);
    gold.setReuse(profile(new ReuseProfile.Guardrails(false, false, null, List.of(), null)).toJson());

    assertThatThrownBy(() -> library.reuse(userId, gold.getId(), Json.obj(), Use.CHAT, "chat:x", false))
        .isInstanceOf(ValidationException.class);

    gold.setStatus(WorkflowStatus.APPROVED);
    assertThat(library.reuse(userId, gold.getId(), Json.obj(), Use.CHAT, "chat:x", false)).isNotNull();
  }

  @Test
  void aScheduleOnlyGetsWorkflowsTheUserAllowedForTheirCurrentSteps() {
    assertThat(library.find(userId, "check the gold rate", Use.SCHEDULE, 1))
        .as("scheduled runs are off by default").isEmpty();

    gold.setStatus(WorkflowStatus.DRAFT);
    library.describe(userId, gold.getId(),
        profile(new ReuseProfile.Guardrails(true, true, null, List.of(), "made-up")));
    assertThat(ReuseProfile.from(gold.getReuse()).guardrails().unattendedFingerprint())
        .as("the server pins the permission to the steps as they are")
        .isEqualTo(gold.getGraphFingerprint());
    assertThat(library.find(userId, "check the gold rate", Use.SCHEDULE, 1)).hasSize(1);
    assertThat(library.reuse(userId, gold.getId(), Json.obj(), Use.SCHEDULE, "scheduled_task:t", false)
        .triggerSource()).isEqualTo("library:scheduled_task:t");

    gold.setGraphFingerprint("edited");
    assertThat(library.find(userId, "check the gold rate", Use.SCHEDULE, 1)).isEmpty();
    assertThatThrownBy(() -> library.reuse(userId, gold.getId(), Json.obj(), Use.SCHEDULE,
        "scheduled_task:t", false)).hasMessageContaining("allow them again");
  }

  @Test
  void aScheduleNeedsAStrongMatchNotALooseOne() {
    gold.setReuse(profile(new ReuseProfile.Guardrails(true, true, null, List.of(), null))
        .withGuardrails(new ReuseProfile.Guardrails(true, true, null, List.of(),
            gold.getGraphFingerprint())).toJson());

    assertThat(library.find(userId, "gold", Use.CHAT, 1)).hasSize(1);
    assertThat(library.find(userId, "rate my restaurant visit and email the gold card team",
        Use.SCHEDULE, 1)).isEmpty();
  }

  @Test
  void requiredInputsMustBeGiven() {
    gold.setReuse(new ReuseProfile("Check the gold rate", List.of(),
        List.of(new ReuseProfile.Input("city", "Where", true)), null, null).toJson());

    assertThatThrownBy(() -> library.reuse(userId, gold.getId(), Json.obj(), Use.CHAT, "chat:x", true))
        .hasMessageContaining("needs city");
    assertThat(library.reuse(userId, gold.getId(), Json.obj().put("city", "Chennai"), Use.CHAT,
        "chat:x", true)).isNotNull();
  }

  @Test
  void theDailyLimitCountsEveryReuseSinceMidnight() {
    gold.setReuse(profile(new ReuseProfile.Guardrails(true, false, 2, List.of(), null)).toJson());
    when(runs.countStartedSince(eq(gold.getId()), eq(Instant.parse("2026-10-22T00:00:00Z")),
        eq("%library:%"))).thenReturn(2L);

    assertThatThrownBy(() -> library.reuse(userId, gold.getId(), Json.obj(), Use.CHAT, "chat:x", true))
        .hasMessageContaining("its limit");
  }

  @Test
  void someoneElsesWorkflowIsNotFound() {
    assertThatThrownBy(() -> library.reuse(UUID.randomUUID(), gold.getId(), Json.obj(), Use.CHAT,
        "chat:x", true)).isInstanceOf(NotFoundException.class);
  }

  @Test
  void describingWithNothingTakesItOutOfTheLibrary() {
    library.describe(userId, gold.getId(), null);

    assertThat(gold.getReuse()).isNull();
    assertThat(library.list(userId)).isEmpty();
  }

  private WorkflowEntity workflow(String name, WorkflowStatus status, ReuseProfile profile) {
    WorkflowEntity w = new WorkflowEntity();
    w.setId(UUID.randomUUID());
    w.setUserId(userId);
    w.setName(name);
    w.setDescription("Looks up the gold rate and emails it");
    w.setGraph(Json.obj());
    w.setGraphFingerprint("fp-1");
    w.setOrigin(WorkflowOrigin.USER_BUILT);
    w.setStatus(status);
    w.setVersion(1);
    w.setReuse(profile.toJson());
    return w;
  }

  private static ReuseProfile profile(ReuseProfile.Guardrails guardrails) {
    return new ReuseProfile("Check today's gold rate and email it to me",
        List.of("check the gold rate", "what is gold trading at today"), List.of(), guardrails, null);
  }
}
