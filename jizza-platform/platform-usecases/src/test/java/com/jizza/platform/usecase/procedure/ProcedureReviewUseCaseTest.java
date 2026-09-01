package com.jizza.platform.usecase.procedure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.dto.WorkflowDto;
import com.jizza.engine.api.enums.WorkflowOrigin;
import com.jizza.engine.api.enums.WorkflowStatus;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.procedure.ProcedureService;
import com.jizza.engine.api.workflow.WorkflowRegistry;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Rollback and disable: ownership is checked and the outcome is what gets audited. */
class ProcedureReviewUseCaseTest {

  private final UUID userId = UUID.randomUUID();
  private final UUID workflowId = UUID.randomUUID();
  private WorkflowDto current;
  private ProcedureService procedures;
  private WorkflowRegistry registry;
  private AuditLog audit;
  private ProcedureReviewUseCase useCase;

  private WorkflowDto dto(int version) {
    return new WorkflowDto(workflowId, userId, "Weekly filing", "desc", null, null, null,
        WorkflowOrigin.AGENT_AUTHORED, WorkflowStatus.APPROVED, null, version, null, Instant.now());
  }

  @BeforeEach
  void setUp() {
    current = dto(2);
    procedures = mock(ProcedureService.class);
    registry = mock(WorkflowRegistry.class);
    when(registry.find(workflowId)).thenAnswer(inv -> Optional.of(current));
    audit = mock(AuditLog.class);
    useCase = new ProcedureReviewUseCase(procedures, registry, audit);
  }

  @Test
  void rollbackDelegatesToTheEngineAndAuditsTheResultingVersion() {
    // The engine bumps the version when it rolls back; simulate that happening mid-call.
    org.mockito.Mockito.doAnswer(inv -> { current = dto(3); return null; })
        .when(procedures).rollback(workflowId, 1);

    useCase.rollback(userId, workflowId, 1);

    verify(procedures).rollback(workflowId, 1);
    verify(audit).record(eq(userId), any(), any(), eq("procedure.rolled_back"), eq("Weekly filing"),
        any());
  }

  @Test
  void disableDelegatesToTheEngineAndAuditsIt() {
    useCase.disable(userId, workflowId);

    verify(procedures).disable(workflowId);
    verify(audit).record(eq(userId), any(), any(), eq("procedure.disabled"), eq("Weekly filing"),
        any());
  }

  @Test
  void anotherUsersProcedureIsReportedMissingAndNothingRuns() {
    UUID somebodyElse = UUID.randomUUID();

    assertThatThrownBy(() -> useCase.rollback(somebodyElse, workflowId, 1))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> useCase.disable(somebodyElse, workflowId))
        .isInstanceOf(NotFoundException.class);

    verify(procedures, org.mockito.Mockito.never()).rollback(any(), org.mockito.ArgumentMatchers.anyInt());
    verify(procedures, org.mockito.Mockito.never()).disable(any());
  }
}
