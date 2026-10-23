package com.jizza.engine.workflow.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jizza.engine.api.enums.AutonomyRule;
import com.jizza.engine.api.enums.NodeType;
import com.jizza.engine.api.enums.NotificationKind;
import com.jizza.engine.api.enums.RunStatus;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.workflow.graph.WorkflowEdge;
import com.jizza.engine.api.workflow.graph.WorkflowGraph;
import com.jizza.engine.api.workflow.graph.WorkflowNode;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The "MCP tool" step: one tool of a server added under Connectors, under the guardrails. */
class McpToolStepTest {

  private static WorkflowGraph line(String config) {
    return new WorkflowGraph(
        List.of(new WorkflowNode("start", NodeType.TRIGGER, "start", null, null),
            new WorkflowNode("quote", NodeType.MCP_TOOL, "quote", Json.read(config),
                new WorkflowNode.RetryPolicy(1, 1, 1.0, 10)),
            new WorkflowNode("finish", NodeType.END, "finish", null, null)),
        List.of(new WorkflowEdge("start", "quote", null), new WorkflowEdge("quote", "finish", null)));
  }

  private static String config(EngineHarness h, String tool) {
    return "{\"serverId\":\"" + h.mcpServerId + "\",\"tool\":\"" + tool + "\","
        + "\"arguments\":{\"symbol\":\"{{ input.symbol }}\",\"qty\":\"{{ input.qty }}\",\"note\":\"\"}}";
  }

  @Test
  void callsTheToolWithMappedArgumentsAndKeepsTheirTypes() {
    EngineHarness harness = new EngineHarness(line("{}"));
    harness.workflow.setGraph(line(config(harness, "get_quote")).toJson());
    harness.nextToolResult = com.jizza.engine.api.tool.ToolResult.ok(Json.obj().put("price", 2810.5));

    var run = harness.engine.start(harness.workflow.getId(), 1,
        Json.obj().put("symbol", "INFY").put("qty", 3), "manual");

    assertThat(run.status()).isEqualTo(RunStatus.DONE);
    // A single placeholder keeps the number a number; an empty optional field is left out.
    assertThat(harness.mcpCalls).containsExactly("get_quote {\"symbol\":\"INFY\",\"qty\":3}");
  }

  @Test
  void aToolTheUserTurnedOffDoesNotRun() {
    EngineHarness harness = new EngineHarness(line("{}"));
    harness.workflow.setGraph(line(config(harness, "place_order")).toJson());

    var run = harness.engine.start(harness.workflow.getId(), 1, Json.obj(), "manual");

    assertThat(run.status()).isNotEqualTo(RunStatus.DONE);
    assertThat(harness.mcpCalls).isEmpty();
  }

  @Test
  void asksFirstWhenTheGuardrailsSaySoAndRunsOnceApproved() {
    EngineHarness harness = new EngineHarness(line("{}"));
    harness.workflow.setGraph(line(config(harness, "get_quote")).toJson());
    harness.toolRule = AutonomyRule.ASK;

    var run = harness.engine.start(harness.workflow.getId(), 1, Json.obj().put("symbol", "TCS"), "manual");

    assertThat(run.status()).isEqualTo(RunStatus.PAUSED_WAITING_INSTRUCTION);
    assertThat(harness.mcpCalls).isEmpty();
    assertThat(harness.raised).extracting(com.jizza.engine.api.dto.NotificationDto::kind)
        .contains(NotificationKind.PERMISSION_REQUEST);

    var resumed = harness.engine.resume(run.id(), Json.obj().put("approved", true));
    assertThat(resumed.status()).isEqualTo(RunStatus.DONE);
    assertThat(harness.mcpCalls).hasSize(1);
  }

  @Test
  void aBlockedToolNeverRuns() {
    EngineHarness harness = new EngineHarness(line("{}"));
    harness.workflow.setGraph(line(config(harness, "get_quote")).toJson());
    harness.toolRule = AutonomyRule.DENY;

    harness.engine.start(harness.workflow.getId(), 1, Json.obj(), "manual");

    assertThat(harness.mcpCalls).isEmpty();
  }

  @Test
  void theStepMustNameAServerAndATool() {
    assertThatThrownBy(() -> new WorkflowValidator().validate(line("{\"tool\":\"get_quote\"}")))
        .isInstanceOf(ValidationException.class).hasMessageContaining("serverId");
  }
}
