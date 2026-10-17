package com.jizza.engine.api.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jizza.engine.api.enums.NodeType;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.workflow.graph.WorkflowEdge;
import com.jizza.engine.api.workflow.graph.WorkflowGraph;
import com.jizza.engine.api.workflow.graph.WorkflowNode;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorkflowGraphTest {

  private WorkflowNode n(String id, NodeType t) { return new WorkflowNode(id, t, id, null, null); }

  @Test
  void validatesAWellFormedGraph() {
    var g = new WorkflowGraph(
        List.of(n("t", NodeType.TRIGGER), n("a", NodeType.AGENT_STEP), n("e", NodeType.END)),
        List.of(new WorkflowEdge("t", "a", null), new WorkflowEdge("a", "e", null)));
    g.validate();
    assertThat(g.trigger().id()).isEqualTo("t");
    assertThat(g.next("t")).contains("a");
  }

  @Test
  void rejectsGraphWithoutExactlyOneTrigger() {
    var g = new WorkflowGraph(List.of(n("a", NodeType.AGENT_STEP)), List.of());
    assertThatThrownBy(g::validate).isInstanceOf(ValidationException.class)
        .hasMessageContaining("exactly one trigger");
  }

  @Test
  void rejectsCycles() {
    var g = new WorkflowGraph(
        List.of(n("t", NodeType.TRIGGER), n("a", NodeType.AGENT_STEP), n("b", NodeType.AGENT_STEP)),
        List.of(new WorkflowEdge("t", "a", null), new WorkflowEdge("a", "b", null),
            new WorkflowEdge("b", "a", null)));
    assertThatThrownBy(g::validate).isInstanceOf(ValidationException.class).hasMessageContaining("cycle");
  }

  @Test
  void rejectsDanglingEdges() {
    var g = new WorkflowGraph(List.of(n("t", NodeType.TRIGGER)),
        List.of(new WorkflowEdge("t", "ghost", null)));
    assertThatThrownBy(g::validate).isInstanceOf(ValidationException.class).hasMessageContaining("ghost");
  }

  @Test
  void fingerprintIsStableAcrossOrderingAndChangesWithContent() {
    var a = new WorkflowGraph(List.of(n("t", NodeType.TRIGGER), n("x", NodeType.AGENT_STEP)),
        List.of(new WorkflowEdge("t", "x", null)));
    var reordered = new WorkflowGraph(List.of(n("x", NodeType.AGENT_STEP), n("t", NodeType.TRIGGER)),
        List.of(new WorkflowEdge("t", "x", null)));
    var different = new WorkflowGraph(List.of(n("t", NodeType.TRIGGER), n("y", NodeType.AGENT_STEP)),
        List.of(new WorkflowEdge("t", "y", null)));
    assertThat(a.fingerprint()).isEqualTo(reordered.fingerprint());
    assertThat(a.fingerprint()).isNotEqualTo(different.fingerprint());
  }

  @Test
  void roundTripsThroughJson() {
    var g = new WorkflowGraph(List.of(n("t", NodeType.TRIGGER), n("x", NodeType.TRANSFORM)),
        List.of(new WorkflowEdge("t", "x", null)));
    assertThat(WorkflowGraph.from(g.toJson()).fingerprint()).isEqualTo(g.fingerprint());
  }
}
