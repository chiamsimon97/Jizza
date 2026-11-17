package com.jizza.engine.workflow.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.enums.NodeType;
import com.jizza.engine.api.enums.RunStatus;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.workflow.graph.WorkflowEdge;
import com.jizza.engine.api.workflow.graph.WorkflowGraph;
import com.jizza.engine.api.workflow.graph.WorkflowNode;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Plan 06: the person taps seats on a picture of the seat map, and the browser clicks them. */
class TapPictureTest {

  private static WorkflowNode node(String id, NodeType type, String config) {
    return new WorkflowNode(id, type, id, config == null ? null : Json.read(config),
        new WorkflowNode.RetryPolicy(3, 1, 1, 10));
  }

  private static EngineHarness harness() {
    var nodes = List.of(node("start", NodeType.TRIGGER, null),
        node("seats", NodeType.DEVICE_ACTION, """
            {"preset":"web","action":"browser.tapPoints","version":1,"target":"sameBrowser",
             "inputs":{"element":{"strategies":[{"by":"css","value":"#seat-map"}],"label":"Seat map"}}}
            """),
        node("finish", NodeType.END, null));
    return new EngineHarness(new WorkflowGraph(nodes, List.of(
        new WorkflowEdge("start", "seats", null), new WorkflowEdge("seats", "finish", null))));
  }

  @Test
  void showsAPictureOfTheAreaThenTapsWhereThePersonTapped() {
    EngineHarness harness = harness();
    harness.deviceInputSchemas.put("browser.tapPoints", Json.read("""
        {"type":"object","properties":{"session":{"type":"string"},
          "element":{"$ref":"device-actions.json#/schemas/locatorBundle"},
          "points":{"type":"array","items":{"type":"object"}}},"required":["element"]}
        """));
    harness.deviceOutputs.put("browser.screenshot", Json.obj().put("file", "file-1"));
    harness.deviceOutputs.put("browser.tapPoints", Json.obj().put("tapped", 2));

    var run = harness.engine.start(harness.workflow.getId(), 1, Json.obj(), "manual");

    assertThat(run.status()).isEqualTo(RunStatus.PAUSED_WAITING_INSTRUCTION);
    assertThat(harness.deviceRequests).hasSize(1);
    assertThat(harness.deviceRequests.get(0).action()).isEqualTo("browser.screenshot");
    assertThat(harness.deviceRequests.get(0).input().path("element").path("label").asText())
        .isEqualTo("Seat map");
    JsonNode field = null;
    for (JsonNode f : harness.raised.get(0).context().path("form").path("fields")) {
      if ("points".equals(f.path("key").asText())) field = f;
    }
    assertThat(field).isNotNull();
    assertThat(field.path("type").asText()).isEqualTo("taps");
    assertThat(field.path("label").asText()).isEqualTo("Where to tap");
    assertThat(field.path("picture").path("file").asText()).isEqualTo("file-1");

    var done = harness.engine.resume(run.id(), Json.obj().put("approved", true)
        .set("inputs", Json.obj().set("points",
            Json.mapper().createArrayNode().add("0.25,0.5").add("0.75,0.5").add("2,2"))));

    assertThat(done.status()).isEqualTo(RunStatus.DONE);
    var tap = harness.deviceRequests.get(1);
    assertThat(tap.action()).isEqualTo("browser.tapPoints");
    assertThat(tap.input().path("points").toString())
        .isEqualTo("[{\"x\":0.25,\"y\":0.5},{\"x\":0.75,\"y\":0.5}]");
  }
}
