package com.jizza.engine.workflow.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jizza.engine.api.dto.RunHistoryDto;
import com.jizza.engine.api.enums.NodeType;
import com.jizza.engine.api.enums.RunStatus;
import com.jizza.engine.api.enums.RunStepStatus;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.workflow.graph.WorkflowEdge;
import com.jizza.engine.api.workflow.graph.WorkflowGraph;
import com.jizza.engine.api.workflow.graph.WorkflowNode;
import com.jizza.engine.persistence.entity.WorkflowEntity;
import com.jizza.engine.persistence.entity.WorkflowRunEntity;
import com.jizza.engine.persistence.entity.WorkflowRunStepEntity;
import com.jizza.engine.persistence.repository.WorkflowRepository;
import com.jizza.engine.persistence.repository.WorkflowRunRepository;
import com.jizza.engine.persistence.repository.WorkflowRunRow;
import com.jizza.engine.persistence.repository.WorkflowRunStepRepository;
import com.jizza.engine.persistence.repository.WorkflowVersionRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The run history list and a single run's trace, over mocked run and step tables. */
class DefaultRunHistoryTest {

  private static final Instant NOW = Instant.parse("2026-10-22T12:00:00Z");

  private final UUID userId = UUID.randomUUID();
  private final UUID orders = UUID.randomUUID();
  private final UUID invoices = UUID.randomUUID();
  private final List<WorkflowRunRow> rows = new ArrayList<>();

  private WorkflowRunRepository runs;
  private WorkflowRunStepRepository steps;
  private WorkflowRepository workflows;
  private DefaultRunHistory history;

  @BeforeEach
  void setUp() {
    runs = mock(WorkflowRunRepository.class);
    steps = mock(WorkflowRunStepRepository.class);
    workflows = mock(WorkflowRepository.class);
    when(runs.findRows(eq(userId), any(), any(), any())).thenAnswer(inv -> rows);
    when(steps.countByRunAndStatus(anyCollection())).thenReturn(List.of());
    when(workflows.findAllById(any())).thenReturn(List.of(
        workflow(orders, "Sync orders", null), workflow(invoices, "Send invoices", null)));
    history = new DefaultRunHistory(runs, steps, workflows, mock(WorkflowVersionRepository.class),
        Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private WorkflowEntity workflow(UUID id, String name, WorkflowGraph graph) {
    WorkflowEntity w = new WorkflowEntity();
    w.setId(id);
    w.setName(name);
    w.setVersion(1);
    if (graph != null) w.setGraph(graph.toJson());
    return w;
  }

  private void row(UUID workflowId, RunStatus status, String trigger, Instant at, long tookMs,
                   String error) {
    rows.add(new WorkflowRunRow(UUID.randomUUID(), workflowId, 1, trigger, status, "hook", error,
        at, at.plusMillis(tookMs)));
  }

  private RunHistoryDto.Query query(UUID workflowId, Set<RunStatus> statuses, Set<String> triggers,
                                    String search) {
    return new RunHistoryDto.Query(NOW.minusSeconds(6 * 3600), NOW, workflowId, statuses,
        triggers, search, 50, 0, ZoneOffset.UTC);
  }

  @Test
  void listsRunsWithNamesDurationsAndStatsForTheWholeMatch() {
    row(orders, RunStatus.DONE, "manual:" + userId, NOW.minusSeconds(600), 1_000, null);
    row(orders, RunStatus.FAILED, "webhook:abc", NOW.minusSeconds(4000), 3_000, "boom");
    row(invoices, RunStatus.DONE, "scheduled_task:x", NOW.minusSeconds(8000), 2_000, null);
    row(invoices, RunStatus.WAITING, "manual:" + userId, NOW.minusSeconds(9000), 10, null);

    var page = history.search(userId, query(null, Set.of(), Set.of(), null));

    assertThat(page.total()).isEqualTo(4);
    assertThat(page.items().get(0).workflowName()).isEqualTo("Sync orders");
    assertThat(page.items().get(0).durationMs()).isEqualTo(1_000);
    assertThat(page.items().get(1).failedNode()).isEqualTo("hook");
    assertThat(page.items().get(3).endedAt()).isNull();
    assertThat(page.stats().successRate()).isEqualTo(2.0 / 3);
    assertThat(page.stats().avgDurationMs()).isEqualTo(2_000);
    assertThat(page.triggers()).containsEntry("manual", 2L).containsEntry("webhook", 1L)
        .containsEntry("schedule", 1L);
    assertThat(page.bucketUnit()).isEqualTo("hour");
    assertThat(page.buckets()).hasSize(6);
    assertThat(page.buckets().get(5).done()).isEqualTo(1);
    assertThat(page.buckets().get(4).failed()).isEqualTo(1);
  }

  @Test
  void filtersByWorkflowStatusTriggerAndTextButKeepsEveryWorkflowsCount() {
    row(orders, RunStatus.DONE, "manual:" + userId, NOW.minusSeconds(600), 1_000, null);
    row(orders, RunStatus.FAILED, "webhook:abc", NOW.minusSeconds(700), 1_000, "Timeout calling API");
    row(invoices, RunStatus.DONE, "manual:" + userId, NOW.minusSeconds(800), 1_000, null);

    var byWorkflow = history.search(userId, query(orders, Set.of(), Set.of(), null));
    assertThat(byWorkflow.total()).isEqualTo(2);
    assertThat(byWorkflow.workflows()).hasSize(2);

    assertThat(history.search(userId, query(null, Set.of(RunStatus.FAILED), Set.of(), null))
        .total()).isEqualTo(1);
    assertThat(history.search(userId, query(null, Set.of(), Set.of("manual"), null)).total())
        .isEqualTo(2);
    assertThat(history.search(userId, query(null, Set.of(), Set.of(), "timeout")).total())
        .isEqualTo(1);
    assertThat(history.search(userId, query(null, Set.of(), Set.of(), "send inv")).total())
        .isEqualTo(1);
  }

  @Test
  void traceLaysTheStepsOverTheGraphWithInputsOutputsAndTimes() {
    WorkflowGraph graph = new WorkflowGraph(List.of(
        new WorkflowNode("start", NodeType.TRIGGER, "When called", null, null),
        new WorkflowNode("hook", NodeType.HTTP_REQUEST, "Post order", null, null),
        new WorkflowNode("finish", NodeType.END, null, null, null)),
        List.of(new WorkflowEdge("start", "hook", null), new WorkflowEdge("hook", "finish", null)));
    WorkflowEntity w = workflow(orders, "Sync orders", graph);
    when(workflows.findById(orders)).thenReturn(Optional.of(w));

    WorkflowRunEntity run = new WorkflowRunEntity();
    run.setId(UUID.randomUUID());
    run.setUserId(userId);
    run.setWorkflowId(orders);
    run.setVersion(1);
    run.setTriggerSource("manual:" + userId);
    run.setStatus(RunStatus.FAILED);
    run.setCurrentNode("hook");
    run.setError("404");
    run.setContext(Json.obj().set("input", Json.obj().put("id", 7)));
    run.setCreatedAt(NOW.minusSeconds(10));
    run.setUpdatedAt(NOW.minusSeconds(5));
    when(runs.findById(run.getId())).thenReturn(Optional.of(run));

    WorkflowRunStepEntity start = step(run.getId(), "start", RunStepStatus.DONE, 0, 5, null);
    WorkflowRunStepEntity hook = step(run.getId(), "hook", RunStepStatus.FAILED, 5, -1, "404");
    hook.setInput(Json.obj().set("settings", Json.obj().put("url", "https://x/7")));
    hook.setAttempts(3);
    when(steps.findByRunIdOrderByStartedAtAsc(run.getId())).thenReturn(List.of(start, hook));

    var trace = history.trace(userId, run.getId());

    assertThat(trace.run().workflowName()).isEqualTo("Sync orders");
    assertThat(trace.run().stepCount()).isEqualTo(2);
    assertThat(trace.run().failedSteps()).isEqualTo(1);
    assertThat(trace.input().path("id").asInt()).isEqualTo(7);
    assertThat(trace.nodes()).extracting(RunHistoryDto.TraceNode::label)
        .containsExactly("When called", "Post order", "finish");
    assertThat(trace.edges()).hasSize(2);
    assertThat(trace.steps().get(0).durationMs()).isEqualTo(5);
    assertThat(trace.steps().get(1).label()).isEqualTo("Post order");
    assertThat(trace.steps().get(1).type()).isEqualTo(NodeType.HTTP_REQUEST);
    assertThat(trace.steps().get(1).input().path("settings").path("url").asText())
        .isEqualTo("https://x/7");
    assertThat(trace.steps().get(1).error()).isEqualTo("404");
    assertThat(trace.steps().get(1).durationMs()).isNull();
  }

  @Test
  void someoneElsesRunIsNotFound() {
    WorkflowRunEntity run = new WorkflowRunEntity();
    run.setId(UUID.randomUUID());
    run.setUserId(UUID.randomUUID());
    when(runs.findById(run.getId())).thenReturn(Optional.of(run));

    assertThatThrownBy(() -> history.trace(userId, run.getId()))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void aChildRunKnowsItsParent() {
    UUID parent = UUID.randomUUID();
    assertThat(DefaultRunHistory.parentOf("manual:subworkflow:" + parent)).isEqualTo(parent);
    assertThat(DefaultRunHistory.parentOf("manual:" + parent)).isNull();
    assertThat(RunHistoryDto.triggerKind("manual:subworkflow:" + parent)).isEqualTo("subworkflow");
    assertThat(RunHistoryDto.triggerKind("skill:abc")).isEqualTo("agent");
  }

  private WorkflowRunStepEntity step(UUID runId, String nodeId, RunStepStatus status,
                                     long startOffsetMs, long tookMs, String error) {
    WorkflowRunStepEntity s = new WorkflowRunStepEntity();
    s.setId(UUID.randomUUID());
    s.setRunId(runId);
    s.setNodeId(nodeId);
    s.setStatus(status);
    s.setStartedAt(NOW.minusSeconds(10).plusMillis(startOffsetMs));
    if (tookMs >= 0) s.setFinishedAt(s.getStartedAt().plusMillis(tookMs));
    s.setError(error);
    s.setAttempts(1);
    return s;
  }
}
