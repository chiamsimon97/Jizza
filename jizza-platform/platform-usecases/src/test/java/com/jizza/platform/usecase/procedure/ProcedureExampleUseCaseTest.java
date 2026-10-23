package com.jizza.platform.usecase.procedure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jizza.engine.api.dto.AgentDto;
import com.jizza.engine.api.dto.TaskDto;
import com.jizza.engine.api.enums.NodeType;
import com.jizza.engine.api.enums.ScheduleType;
import com.jizza.engine.api.enums.TaskStatus;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.orchestrator.AgentDirectory;
import com.jizza.engine.api.procedure.ProcedureService;
import com.jizza.engine.api.scheduler.SchedulerService;
import com.jizza.engine.api.scheduler.TaskSpec;
import com.jizza.engine.api.workflow.graph.WorkflowGraph;
import com.jizza.engine.api.workflow.graph.WorkflowNode;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ProcedureExampleUseCaseTest {

  private final UUID userId = UUID.randomUUID();
  private final AgentDirectory agents = mock(AgentDirectory.class);
  private final SchedulerService scheduler = mock(SchedulerService.class);
  private final ProcedureService procedures = mock(ProcedureService.class);
  private final ProcedureExampleUseCase example =
      new ProcedureExampleUseCase(agents, scheduler, procedures);

  private AgentDto agent(String name, boolean orchestrator, boolean enabled) {
    return new AgentDto(UUID.randomUUID(), userId, name.toLowerCase(), name, "d", "p",
        orchestrator, enabled);
  }

  @Test
  void addsARepeatingTaskAndAProcedureLearnedFromItForReview() {
    AgentDto kai = agent("Kai", false, true);
    when(agents.list(userId)).thenReturn(List.of(agent("Jizza", true, true), kai));
    UUID taskId = UUID.randomUUID();
    when(scheduler.schedule(any())).thenReturn(new TaskDto(taskId, userId, kai.id(), "Kai",
        ProcedureExampleUseCase.TASK_TITLE, null, ScheduleType.RECURRING, "0 0 9 * * MON", null,
        null, null, null, null, TaskStatus.PENDING, null, null, true));

    example.create(userId);

    ArgumentCaptor<TaskSpec> spec = ArgumentCaptor.forClass(TaskSpec.class);
    verify(scheduler).schedule(spec.capture());
    assertThat(spec.getValue().agentId()).as("a specialist owns it, not Jizza").isEqualTo(kai.id());
    assertThat(spec.getValue().repetitive()).isTrue();
    verify(procedures).propose(eq(kai.id()), eq(taskId), eq(ProcedureExampleUseCase.PROCEDURE_NAME),
        any(), any());
  }

  @Test
  void theExampleUsesOnlyBuiltInStepsAndIsAValidGraph() {
    WorkflowGraph graph = WorkflowGraph.from(ProcedureExampleUseCase.graph());
    graph.validate();
    assertThat(graph.nodes()).extracting(WorkflowNode::type).doesNotContain(
        NodeType.AGENT_STEP, NodeType.CONNECTOR_TOOL, NodeType.MCP_TOOL, NodeType.HTTP_REQUEST);
  }

  @Test
  void withoutASpecialistItSaysWhatToDo() {
    when(agents.list(userId)).thenReturn(List.of(agent("Jizza", true, true)));
    assertThatThrownBy(() -> example.create(userId)).isInstanceOf(ValidationException.class)
        .hasMessageContaining("agent");
  }
}
