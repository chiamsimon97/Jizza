package com.jizza.engine.workflow.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.enums.NodeType;
import com.jizza.engine.api.workflow.graph.WorkflowNode;
import org.junit.jupiter.api.Test;

class StepOverridesTest {

  private final WorkflowNode tool = new WorkflowNode("n1", NodeType.CONNECTOR_TOOL, "Create",
      Json.read("{\"toolId\":\"t\",\"arguments\":{\"title\":\"Fixed\"},\"inputs\":[{\"name\":\"a\",\"value\":\"1\"}]}"), null);

  @Test
  void pointsASettingAtTheInput() {
    var overrides = Json.read("{\"n1\":[{\"path\":[\"arguments\",\"title\"],\"value\":\"{{ input.subject }}\"},"
        + "{\"path\":[\"inputs\",0,\"value\"],\"value\":\"{{ input.a }}\"},{\"path\":[\"arguments\",\"new\"],\"value\":\"input.x\"}]}");
    WorkflowNode out = StepOverrides.apply(tool, overrides);
    assertThat(out.config().path("arguments").path("title").asText()).isEqualTo("{{ input.subject }}");
    assertThat(out.config().path("arguments").path("new").asText()).isEqualTo("input.x");
    assertThat(out.config().path("inputs").get(0).path("value").asText()).isEqualTo("{{ input.a }}");
    assertThat(tool.config().path("arguments").path("title").asText()).as("the graph is untouched").isEqualTo("Fixed");
  }

  @Test
  void neverSetsAnythingButAReferenceToTheInput() {
    var overrides = Json.read("{\"n1\":[{\"path\":[\"arguments\",\"title\"],\"value\":\"{{ nodes.x.secret }}\"},"
        + "{\"path\":[\"toolId\"],\"value\":\"other\"},{\"path\":[\"retry\"],\"value\":\"input.x\"}]}");
    assertThat(StepOverrides.apply(tool, overrides)).isSameAs(tool);
  }

  @Test
  void leavesOtherStepsAlone() {
    assertThat(StepOverrides.apply(tool, Json.read("{\"n2\":[{\"path\":[\"x\"],\"value\":\"input.x\"}]}"))).isSameAs(tool);
    assertThat(StepOverrides.apply(tool, null)).isSameAs(tool);
  }
}
