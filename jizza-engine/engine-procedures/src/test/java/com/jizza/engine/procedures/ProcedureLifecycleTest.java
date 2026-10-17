package com.jizza.engine.procedures;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.dto.NotificationDto;
import com.jizza.engine.api.enums.EscalationState;
import com.jizza.engine.api.enums.NodeType;
import com.jizza.engine.api.enums.NotificationKind;
import com.jizza.engine.api.enums.NotificationStatus;
import com.jizza.engine.api.enums.ScheduleType;
import com.jizza.engine.api.enums.TaskStatus;
import com.jizza.engine.api.enums.WorkflowOrigin;
import com.jizza.engine.api.enums.WorkflowStatus;
import com.jizza.engine.api.error.JizzaException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.notification.Answer;
import com.jizza.engine.api.notification.NotificationBus;
import com.jizza.engine.api.notification.NotificationContext;
import com.jizza.engine.api.scheduler.SchedulerService;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.workflow.graph.WorkflowEdge;
import com.jizza.engine.api.workflow.graph.WorkflowGraph;
import com.jizza.engine.api.workflow.graph.WorkflowNode;
import com.jizza.engine.persistence.entity.AgentEntity;
import com.jizza.engine.persistence.entity.ScheduledTaskEntity;
import com.jizza.engine.persistence.entity.SkillEntity;
import com.jizza.engine.persistence.entity.WorkflowEntity;
import com.jizza.engine.persistence.entity.WorkflowVersionEntity;
import com.jizza.engine.persistence.repository.AgentRepository;
import com.jizza.engine.persistence.repository.ScheduledTaskRepository;
import com.jizza.engine.persistence.repository.SkillRepository;
import com.jizza.engine.persistence.repository.WorkflowRepository;
import com.jizza.engine.persistence.repository.WorkflowVersionRepository;
import com.jizza.engine.procedures.binding.DefaultBindingEnforcer;
import com.jizza.engine.procedures.binding.DeviationRecorder;
import com.jizza.engine.procedures.service.DefaultProcedureService;
import com.jizza.engine.workflow.engine.WorkflowValidator;
import com.jizza.engine.workflow.registry.DefaultWorkflowRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The learned-procedure lifecycle end to end at the service level (R34-R36): propose, review,
 * approve-and-bind, run exactly, revise, roll back.
 */
class ProcedureLifecycleTest {

  private final UUID userId = UUID.randomUUID();

  private final List<WorkflowEntity> workflowRows = new ArrayList<>();
  private final List<WorkflowVersionEntity> versionRows = new ArrayList<>();
  private final List<SkillEntity> skillRows = new ArrayList<>();
  private final List<NotificationDto> raised = new ArrayList<>();

  private AgentEntity kai;
  private ScheduledTaskEntity task;
  private WorkflowRepository workflows;
  private WorkflowVersionRepository versions;
  private ScheduledTaskRepository tasks;
  private SkillRepository skills;
  private SchedulerService scheduler;
  private DefaultProcedureService procedures;
  private DefaultBindingEnforcer enforcer;
  private DefaultWorkflowRegistry registry;

  private JsonNode graph(String transformExpression) {
    return new WorkflowGraph(
        List.of(new WorkflowNode("start", NodeType.TRIGGER, "start", null, null),
            new WorkflowNode("shape", NodeType.TRANSFORM, "shape",
                Json.obj().put("expression", transformExpression), null),
            new WorkflowNode("finish", NodeType.END, "finish", null, null)),
        List.of(new WorkflowEdge("start", "shape", null),
            new WorkflowEdge("shape", "finish", null))).toJson();
  }

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    kai = new AgentEntity();
    kai.setId(UUID.randomUUID());
    kai.setUserId(userId);
    kai.setKey("finance");
    kai.setName("Kai");
    kai.setSystemPrompt("p");
    kai.setEnabled(true);

    task = new ScheduledTaskEntity();
    task.setId(UUID.randomUUID());
    task.setUserId(userId);
    task.setAgentId(kai.getId());
    task.setTitle("Monthly bill run");
    task.setScheduleType(ScheduleType.RECURRING);
    task.setCronOrRrule("0 0 9 1 * *");
    task.setStatus(TaskStatus.PENDING);
    task.setRepetitive(true);

    AgentRepository agents = mock(AgentRepository.class);
    when(agents.findById(kai.getId())).thenReturn(java.util.Optional.of(kai));

    workflows = mock(WorkflowRepository.class);
    when(workflows.save(any())).thenAnswer(inv -> {
      WorkflowEntity w = inv.getArgument(0);
      if (w.getId() == null) {
        w.setId(UUID.randomUUID());
        w.setCreatedAt(Instant.now());
      }
      workflowRows.removeIf(r -> r.getId().equals(w.getId()));
      workflowRows.add(w);
      return w;
    });
    when(workflows.findById(any())).thenAnswer(inv ->
        workflowRows.stream().filter(w -> w.getId().equals(inv.getArgument(0))).findFirst());
    when(workflows.findBySourceTaskId(any())).thenAnswer(inv -> workflowRows.stream()
        .filter(w -> inv.getArgument(0).equals(w.getSourceTaskId())).toList());

    versions = mock(WorkflowVersionRepository.class);
    when(versions.save(any())).thenAnswer(inv -> {
      WorkflowVersionEntity v = inv.getArgument(0);
      if (v.getId() == null) v.setId(UUID.randomUUID());
      versionRows.removeIf(r -> r.getId().equals(v.getId()));
      versionRows.add(v);
      return v;
    });
    when(versions.findByWorkflowIdAndVersion(any(), org.mockito.ArgumentMatchers.anyInt()))
        .thenAnswer(inv -> versionRows.stream()
            .filter(v -> v.getWorkflowId().equals(inv.getArgument(0))
                && v.getVersion() == (int) inv.getArgument(1))
            .findFirst());
    when(versions.findByWorkflowIdOrderByVersionAsc(any())).thenAnswer(inv -> versionRows.stream()
        .filter(v -> v.getWorkflowId().equals(inv.<UUID>getArgument(0)))
        .sorted(java.util.Comparator.comparingInt(WorkflowVersionEntity::getVersion)).toList());

    tasks = mock(ScheduledTaskRepository.class);
    when(tasks.findById(task.getId())).thenReturn(java.util.Optional.of(task));

    skills = mock(SkillRepository.class);
    when(skills.findByWorkflowId(any())).thenAnswer(inv -> skillRows.stream()
        .filter(s -> s.getWorkflowId().equals(inv.<UUID>getArgument(0))).toList());
    when(skills.save(any())).thenAnswer(inv -> inv.getArgument(0));

    scheduler = mock(SchedulerService.class);
    org.mockito.Mockito.doAnswer(inv -> {
      task.setBoundWorkflowId(inv.getArgument(1));
      task.setBoundVersion(inv.getArgument(2));
      return null;
    }).when(scheduler).bindProcedure(any(), any(), org.mockito.ArgumentMatchers.anyInt());
    org.mockito.Mockito.doAnswer(inv -> {
      task.setBoundWorkflowId(null);
      task.setBoundVersion(null);
      return null;
    }).when(scheduler).unbindProcedure(any());

    NotificationBus bus = new NotificationBus() {
      @Override public NotificationDto raise(NotificationKind kind, UUID agentId,
                                             NotificationContext ctx) {
        NotificationDto dto = new NotificationDto(UUID.randomUUID(), ctx.userId(), agentId, "Kai",
            kind, ctx.body(), ctx.detail(), ctx.taskId(), ctx.workflowId(), ctx.runId(),
            NotificationStatus.UNREAD, null, null, EscalationState.NONE, Instant.now(), null);
        raised.add(dto);
        return dto;
      }

      @Override public NotificationDto answer(UUID id, Answer a) { throw new UnsupportedOperationException(); }
      @Override public List<NotificationDto> list(UUID u, boolean unread) { return raised; }
      @Override public NotificationDto get(UUID id) { throw new UnsupportedOperationException(); }
      @Override public void markRead(UUID id) { }
      @Override public boolean awaitPermission(UUID id, java.time.Duration t) { return false; }
    };
    ObjectProvider<NotificationBus> busProvider = mock(ObjectProvider.class);
    when(busProvider.getIfAvailable()).thenReturn(bus);

    AuditLog audit = mock(AuditLog.class);
    WorkflowValidator validator = new WorkflowValidator();
    registry = new DefaultWorkflowRegistry(workflows, versions, validator);
    var skillStore = new com.jizza.engine.workflow.registry.DefaultSkillStore(skills, workflows);
    procedures = new DefaultProcedureService(workflows, versions, tasks, agents, registry,
        skillStore, validator, scheduler, busProvider, audit);
    enforcer = new DefaultBindingEnforcer(tasks, workflows, versions,
        new DeviationRecorder(busProvider, audit));
  }

  private com.jizza.engine.api.dto.WorkflowDto propose() {
    return procedures.propose(kai.getId(), task.getId(), "Monthly bill run",
        "Pay the recurring bills", graph("input.amount"));
  }

  @Test
  void aRepetitiveJobProducesAProposalForReviewThatIsNotYetUsable() {
    var proposed = propose();

    assertThat(proposed.status()).isEqualTo(WorkflowStatus.PENDING_REVIEW);
    assertThat(proposed.origin()).isEqualTo(WorkflowOrigin.AGENT_AUTHORED);
    assertThat(proposed.owningAgentId()).isEqualTo(kai.getId());
    assertThat(proposed.sourceTaskId()).isEqualTo(task.getId());
    assertThat(task.getBoundWorkflowId()).as("nothing is bound before approval").isNull();

    assertThat(raised).hasSize(1);
    assertThat(raised.get(0).kind()).isEqualTo(NotificationKind.WORKFLOW_REVIEW);
    assertThat(raised.get(0).body()).contains("Kai").contains("needs your review");
  }

  @Test
  void approvingBindsTheProcedureToTheJobThatProducedIt() {
    var proposed = propose();

    procedures.approve(proposed.id());

    assertThat(workflowRows.get(0).getStatus()).isEqualTo(WorkflowStatus.APPROVED);
    verify(scheduler).bindProcedure(task.getId(), proposed.id(), 1);
    assertThat(task.getBoundWorkflowId()).isEqualTo(proposed.id());
    assertThat(task.getBoundVersion()).isEqualTo(1);
  }

  @Test
  void aBoundJobIsAllowedToRunExactlyTheApprovedGraph() {
    var proposed = propose();
    procedures.approve(proposed.id());

    // The approved snapshot is what the run is checked against, and it matches.
    enforcer.assertBound(task.getId(), proposed.id(), 1, workflowRows.get(0).getGraph());
  }

  @Test
  void aGraphThatDoesNotMatchWhatWasApprovedIsRefusedAndRaisesAnIntervention() {
    var proposed = propose();
    procedures.approve(proposed.id());
    raised.clear();

    assertThatThrownBy(() ->
        enforcer.assertBound(task.getId(), proposed.id(), 1, graph("input.amount * 999")))
        .isInstanceOf(JizzaException.class)
        .hasMessageContaining("does not match the version that was approved");

    assertThat(raised).hasSize(1);
    assertThat(raised.get(0).kind()).isEqualTo(NotificationKind.INSTRUCTION_REQUIRED);
  }

  @Test
  void anUnapprovedProcedureCannotBeRunEvenIfSomethingClaimsItIsBound() {
    var proposed = propose();
    task.setBoundWorkflowId(proposed.id());
    task.setBoundVersion(1);

    assertThatThrownBy(() -> enforcer.assertBound(task.getId(), proposed.id(), 1, null))
        .isInstanceOf(JizzaException.class)
        .hasMessageContaining("rather than approved");
  }

  @Test
  void aDisabledProcedureIsRefused() {
    var proposed = propose();
    procedures.approve(proposed.id());
    procedures.disable(proposed.id());

    assertThatThrownBy(() -> enforcer.assertBound(task.getId(), proposed.id(), 1, null))
        .isInstanceOf(JizzaException.class);
  }

  @Test
  void rejectingKeepsItOutOfUseAndReturnsTheFeedback() {
    var proposed = propose();

    procedures.reject(proposed.id(), "Do not pay the gym bill automatically");

    assertThat(workflowRows.get(0).getStatus()).isEqualTo(WorkflowStatus.REJECTED);
    assertThat(workflowRows.get(0).getReviewFeedback())
        .isEqualTo("Do not pay the gym bill automatically");
    assertThat(task.getBoundWorkflowId()).isNull();
  }

  @Test
  void rejectingAnAlreadyApprovedProcedureUnbindsIt() {
    var proposed = propose();
    procedures.approve(proposed.id());
    assertThat(task.getBoundWorkflowId()).isNotNull();

    procedures.reject(proposed.id(), "changed my mind");

    verify(scheduler).unbindProcedure(task.getId());
    assertThat(task.getBoundWorkflowId()).isNull();
  }

  @Test
  void editingAnApprovedProcedureCreatesANewVersionThatMustBeReviewedAgain() {
    var proposed = propose();
    procedures.approve(proposed.id());
    raised.clear();

    var revised = procedures.revise(proposed.id(), graph("input.amount + 1"));

    assertThat(revised.version()).isEqualTo(2);
    assertThat(revised.status()).isEqualTo(WorkflowStatus.PENDING_REVIEW);
    assertThat(task.getBoundWorkflowId()).as("the job stops running it while it is unreviewed")
        .isNull();
    assertThat(raised).hasSize(1);
    assertThat(raised.get(0).kind()).isEqualTo(NotificationKind.WORKFLOW_REVIEW);
  }

  @Test
  void revisingWithTheSameGraphIsRejectedRatherThanChurningVersions() {
    var proposed = propose();
    assertThatThrownBy(() -> procedures.revise(proposed.id(), graph("input.amount")))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("same graph");
  }

  @Test
  void anAgentReProposingForTheSameJobRevisesRatherThanCreatingARival() {
    var first = propose();

    var second = procedures.propose(kai.getId(), task.getId(), "Monthly bill run", "v2",
        graph("input.amount * 2"));

    assertThat(second.id()).isEqualTo(first.id());
    assertThat(second.version()).isEqualTo(2);
    assertThat(workflowRows).hasSize(1);
  }

  @Test
  void historyKeepsEveryVersionAndDiffShowsWhatChanged() {
    var proposed = propose();
    procedures.approve(proposed.id());
    procedures.revise(proposed.id(), graph("input.amount + 1"));

    assertThat(procedures.history(proposed.id())).hasSize(2);

    var diff = procedures.diff(proposed.id(), 1, 2);
    assertThat(diff.changedNodes()).hasSize(1);
    assertThat(diff.changedNodes().get(0)).contains("shape");
    assertThat(diff.addedNodes()).isEmpty();
    assertThat(diff.removedNodes()).isEmpty();
  }

  @Test
  void rollingBackToAnApprovedVersionRestoresAndRebindsIt() {
    var proposed = propose();
    procedures.approve(proposed.id());
    String approvedGraph = workflowRows.get(0).getGraph().toString();
    procedures.revise(proposed.id(), graph("input.amount + 1"));
    procedures.approve(proposed.id());

    procedures.rollback(proposed.id(), 1);

    assertThat(workflowRows.get(0).getGraph().toString()).isEqualTo(approvedGraph);
    assertThat(workflowRows.get(0).getStatus()).isEqualTo(WorkflowStatus.APPROVED);
    assertThat(workflowRows.get(0).getVersion()).isEqualTo(3);
    assertThat(task.getBoundWorkflowId()).isEqualTo(proposed.id());
    assertThat(task.getBoundVersion()).isEqualTo(3);
  }

  @Test
  void rollingBackToAVersionNobodyApprovedIsRefused() {
    var proposed = propose();
    procedures.approve(proposed.id());
    procedures.revise(proposed.id(), graph("input.amount + 1"));

    assertThatThrownBy(() -> procedures.rollback(proposed.id(), 2))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("never approved");
  }

  @Test
  void aProposalWithAMalformedGraphIsRejectedBeforeItReachesTheUser() {
    JsonNode cyclic = new WorkflowGraph(
        List.of(new WorkflowNode("start", NodeType.TRIGGER, "s", null, null),
            new WorkflowNode("a", NodeType.END, "a", null, null)),
        List.of(new WorkflowEdge("start", "a", null),
            new WorkflowEdge("a", "start", null))).toJson();

    assertThatThrownBy(() -> procedures.propose(kai.getId(), task.getId(), "bad", null, cyclic))
        .isInstanceOf(ValidationException.class);
    assertThat(raised).isEmpty();
    verify(scheduler, never()).bindProcedure(any(), any(), org.mockito.ArgumentMatchers.anyInt());
  }

  @Test
  void approvingIsIdempotent() {
    var proposed = propose();
    procedures.approve(proposed.id());
    procedures.approve(proposed.id());

    verify(scheduler).bindProcedure(eq(task.getId()), eq(proposed.id()), eq(1));
  }
}
