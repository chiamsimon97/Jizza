package com.jizza.engine.workflow.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.enums.AutonomyRule;
import com.jizza.engine.api.enums.NodeType;
import com.jizza.engine.api.enums.RunStatus;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.workflow.graph.WorkflowEdge;
import com.jizza.engine.api.workflow.graph.WorkflowGraph;
import com.jizza.engine.api.workflow.graph.WorkflowNode;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Values marked mandatory while recording (config {@code requiredInputs}, or a form field's own
 * {@code required}): a run that leaves one empty stops at the step until it is filled in.
 */
class MandatoryInputsTest {

  private static EngineHarness harness(String config) {
    EngineHarness harness = new EngineHarness(new WorkflowGraph(List.of(
        new WorkflowNode("start", NodeType.TRIGGER, "start", null, null),
        new WorkflowNode("step", NodeType.DEVICE_ACTION, "step", Json.read(config),
            new WorkflowNode.RetryPolicy(1, 1, 1, 10)),
        new WorkflowNode("finish", NodeType.END, "finish", null, null)),
        List.of(new WorkflowEdge("start", "step", null), new WorkflowEdge("step", "finish", null))));
    String element = "\"element\":{\"$ref\":\"device-actions.json#/schemas/locatorBundle\"}";
    // The note is optional to the action itself; only the mark makes it mandatory.
    harness.deviceInputSchemas.put("browser.setText", Json.read("{\"type\":\"object\","
        + "\"properties\":{" + element + ",\"text\":{\"type\":\"string\"},"
        + "\"note\":{\"type\":\"string\"}},\"required\":[\"element\",\"text\"]}"));
    harness.deviceOutputs.put("browser.setText", Json.obj().put("typed", true));
    harness.toolRule = AutonomyRule.ALLOW;
    return harness;
  }

  private static JsonNode field(EngineHarness harness, String key) {
    JsonNode form = harness.raised.get(harness.raised.size() - 1).context().path("form");
    for (JsonNode f : form.path("fields")) if (key.equals(f.path("key").asText())) return f;
    return null;
  }

  private static final String MARKED = """
      {"preset":"web","action":"browser.setText","version":1,"target":"sameBrowser",
       "requiredInputs":["note"],
       "inputs":{"element":{"strategies":[{"by":"css","value":"#n"}],"label":"Name"},
                 "text":"Chiam","note":"{{ input.note }}"}}
      """;

  @Test
  void anEmptyMandatoryValueStopsTheStepUntilFilledIn() {
    EngineHarness harness = harness(MARKED);

    var run = harness.engine.start(harness.workflow.getId(), 1, Json.obj(), "manual");

    assertThat(run.status()).isEqualTo(RunStatus.PAUSED_WAITING_INSTRUCTION);
    assertThat(field(harness, "note").path("required").asBoolean()).isTrue();
    assertThat(field(harness, "note").path("missing").asBoolean()).isTrue();
    assertThat(harness.deviceRequests).isEmpty();

    harness.engine.resume(run.id(), Json.obj().put("approved", true)
        .set("inputs", Json.obj().put("note", "Window seat")));
    assertThat(harness.deviceRequests).hasSize(1);
    assertThat(harness.deviceRequests.get(0).input().path("note").asText()).isEqualTo("Window seat");
  }

  @Test
  void aMandatoryValueGivenToTheRunGoesStraightOn() {
    EngineHarness harness = harness(MARKED);

    var run = harness.engine.start(harness.workflow.getId(), 1,
        Json.obj().put("note", "Aisle"), "manual");

    assertThat(run.status()).isEqualTo(RunStatus.DONE);
  }
}
