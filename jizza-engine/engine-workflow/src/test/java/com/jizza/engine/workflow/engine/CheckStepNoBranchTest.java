package com.jizza.engine.workflow.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jizza.engine.api.enums.NodeType;
import com.jizza.engine.api.enums.RunStatus;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.workflow.graph.WorkflowEdge;
import com.jizza.engine.api.workflow.graph.WorkflowGraph;
import com.jizza.engine.api.workflow.graph.WorkflowNode;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A check step ("is it on the page?") may have a No way out besides its usual one. */
class CheckStepNoBranchTest {

  private static WorkflowNode node(String id, NodeType type, String config) {
    return new WorkflowNode(id, type, id, config == null ? null : Json.read(config),
        new WorkflowNode.RetryPolicy(3, 1, 1, 10));
  }

  private static WorkflowNode web(String id, String action) {
    return node(id, NodeType.DEVICE_ACTION, """
        {"preset":"web","action":"%s","version":1,"target":"sameBrowser",
         "inputs":{"element":{"strategies":[{"by":"text","value":"%s"}]}}}
        """.formatted(action, id));
  }

  /** start → check → (yes) onYes → finish, (no) onNo → finish when {@code withNo}. */
  private static WorkflowGraph graph(boolean withNo) {
    List<WorkflowNode> nodes = List.of(node("start", NodeType.TRIGGER, null),
        web("check", "browser.exists"), web("onYes", "browser.getText"),
        web("onNo", "browser.click"), node("finish", NodeType.END, null));
    List<WorkflowEdge> edges = new java.util.ArrayList<>(List.of(
        new WorkflowEdge("start", "check", null), new WorkflowEdge("check", "onYes", null),
        new WorkflowEdge("onYes", "finish", null), new WorkflowEdge("onNo", "finish", null)));
    if (withNo) edges.add(new WorkflowEdge("check", "onNo", "false"));
    return new WorkflowGraph(nodes, edges);
  }

  private static List<String> actionsRun(EngineHarness h) {
    return h.deviceRequests.stream().map(r -> r.action()).toList();
  }

  @Test
  void yesTakesTheUsualWay() {
    EngineHarness h = new EngineHarness(graph(true));
    h.deviceOutputs.put("browser.exists", Json.obj().put("exists", true).put("visible", true).put("count", 1));
    var run = h.engine.start(h.workflow.getId(), 1, Json.obj(), "manual");
    assertThat(run.status()).isEqualTo(RunStatus.DONE);
    assertThat(actionsRun(h)).containsExactly("browser.exists", "browser.getText");
  }

  @Test
  void noTakesTheNoWay() {
    EngineHarness h = new EngineHarness(graph(true));
    h.deviceOutputs.put("browser.exists", Json.obj().put("exists", false).put("visible", false).put("count", 0));
    var run = h.engine.start(h.workflow.getId(), 1, Json.obj(), "manual");
    assertThat(run.status()).isEqualTo(RunStatus.DONE);
    assertThat(actionsRun(h)).containsExactly("browser.exists", "browser.click");
  }

  @Test
  void withNothingWiredToNoItCarriesOnAsBefore() {
    EngineHarness h = new EngineHarness(graph(false));
    h.deviceOutputs.put("browser.exists", Json.obj().put("exists", false).put("visible", false).put("count", 0));
    var run = h.engine.start(h.workflow.getId(), 1, Json.obj(), "manual");
    assertThat(run.status()).isEqualTo(RunStatus.DONE);
    assertThat(actionsRun(h)).containsExactly("browser.exists", "browser.getText");
  }

  @Test
  void onlyCheckStepsMayForkThisWay() {
    WorkflowGraph bad = new WorkflowGraph(List.of(node("start", NodeType.TRIGGER, null),
        web("read", "browser.getText"), web("a", "browser.click"), web("b", "browser.click"),
        node("finish", NodeType.END, null)),
        List.of(new WorkflowEdge("start", "read", null), new WorkflowEdge("read", "a", null),
            new WorkflowEdge("read", "b", "false"), new WorkflowEdge("a", "finish", null),
            new WorkflowEdge("b", "finish", null)));
    EngineHarness h = new EngineHarness(bad);
    assertThatThrownBy(() -> h.engine.start(h.workflow.getId(), 1, Json.obj(), "manual"))
        .isInstanceOf(ValidationException.class).hasMessageContaining("more than one outgoing");
  }
}
