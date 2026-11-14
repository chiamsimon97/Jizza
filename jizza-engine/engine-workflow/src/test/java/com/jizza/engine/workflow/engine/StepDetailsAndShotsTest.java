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
 * The notes and questions a person attaches to a web step (config {@code details.blocks}), asked
 * on the approval card and kept as the step's {@code details} output; and the pictures taken of
 * each listed item (config {@code screenshots}), shown on the card for the option chosen.
 */
class StepDetailsAndShotsTest {

  private static WorkflowGraph oneStep(String config) {
    return new WorkflowGraph(List.of(
        new WorkflowNode("start", NodeType.TRIGGER, "start", null, null),
        new WorkflowNode("step", NodeType.DEVICE_ACTION, "step", Json.read(config),
            new WorkflowNode.RetryPolicy(1, 1, 1, 10)),
        new WorkflowNode("finish", NodeType.END, "finish", null, null)),
        List.of(new WorkflowEdge("start", "step", null), new WorkflowEdge("step", "finish", null)));
  }

  private static final String DETAILS = """
      ,"details":{"blocks":[
        {"kind":"note","text":"Check the running days before booking"},
        {"kind":"field","name":"berth","label":"Berth preference","type":"select",
         "options":["Lower","Middle","Upper"]},
        {"kind":"field","name":"remarks","label":"Remarks","type":"text",
         "value":"{{ input.remark }}"}]}""";

  private static EngineHarness harness(String extraConfig) {
    EngineHarness harness = new EngineHarness(oneStep("""
        {"preset":"web","action":"browser.setText","version":1,"target":"sameBrowser",
         "inputs":{"element":{"strategies":[{"by":"css","value":"#name"}],"label":"Name"},
                   "text":"Chiam"}%s}
        """.formatted(extraConfig)));
    String element = "\"element\":{\"$ref\":\"device-actions.json#/schemas/locatorBundle\"}";
    harness.deviceInputSchemas.put("browser.setText", Json.read("{\"type\":\"object\","
        + "\"properties\":{" + element + ",\"text\":{\"type\":\"string\"}},"
        + "\"required\":[\"element\",\"text\"]}"));
    harness.deviceOutputs.put("browser.setText", Json.obj().put("typed", true));
    return harness;
  }

  private static JsonNode field(EngineHarness harness, String key) {
    JsonNode form = harness.raised.get(harness.raised.size() - 1).context().path("form");
    for (JsonNode f : form.path("fields")) if (key.equals(f.path("key").asText())) return f;
    return null;
  }

  @Test
  void aStepWithDetailsAlwaysAsksAndKeepsTheAnswersAsItsOutput() {
    EngineHarness harness = harness(DETAILS);
    harness.toolRule = AutonomyRule.ALLOW;

    var run = harness.engine.start(harness.workflow.getId(), 1,
        Json.obj().put("remark", "Window side"), "manual");

    // Allowed by the guardrails, but the details still stop it for the person.
    assertThat(run.status()).isEqualTo(RunStatus.PAUSED_WAITING_INSTRUCTION);
    JsonNode note = field(harness, "_note.0");
    assertThat(note.path("type").asText()).isEqualTo("note");
    assertThat(note.path("label").asText()).isEqualTo("Check the running days before booking");
    JsonNode berth = field(harness, "details.berth");
    assertThat(berth.path("type").asText()).isEqualTo("select");
    assertThat(berth.path("required").asBoolean()).isTrue();
    assertThat(berth.path("missing").asBoolean()).isTrue();
    assertThat(berth.path("options").toString()).isEqualTo("[\"Lower\",\"Middle\",\"Upper\"]");
    // The default rode in from the run's input and can be changed.
    assertThat(field(harness, "details.remarks").path("value").asText()).isEqualTo("Window side");

    harness.engine.resume(run.id(), Json.obj().put("approved", true)
        .set("inputs", Json.obj().put("details.berth", "Lower")));

    // The device was asked without the details; they are the step's own output instead.
    assertThat(harness.deviceRequests).hasSize(1);
    assertThat(harness.deviceRequests.get(0).input().has("details")).isFalse();
    JsonNode output = harness.stepRows.stream()
        .filter(row -> "step".equals(row.getNodeId())).findFirst().orElseThrow().getOutput();
    assertThat(output.path("details").path("berth").asText()).isEqualTo("Lower");
    assertThat(output.path("details").path("remarks").asText()).isEqualTo("Window side");
  }

  @Test
  void anUnansweredQuestionKeepsTheStepWaiting() {
    EngineHarness harness = harness(DETAILS);
    harness.toolRule = AutonomyRule.ALLOW;

    var run = harness.engine.start(harness.workflow.getId(), 1, Json.obj(), "manual");
    assertThat(run.status()).isEqualTo(RunStatus.PAUSED_WAITING_INSTRUCTION);

    // Approved, but the berth was never chosen: it is still missing, so the step asks again.
    harness.engine.resume(run.id(), Json.obj().put("approved", true)
        .set("inputs", Json.obj().put("details.remarks", "none")));
    assertThat(harness.deviceRequests).isEmpty();
    assertThat(field(harness, "details.berth").path("missing").asBoolean()).isTrue();
  }

  @Test
  void itemPicturesAreReadWithTheListAndRideOnTheCard() {
    EngineHarness harness = new EngineHarness(oneStep("""
        {"preset":"web","action":"browser.clickItem","version":1,"target":"sameBrowser",
         "screenshots":true,
         "inputs":{"element":{"strategies":[{"by":"css","value":".train"}],"label":"Train"},
                   "item":"{{ input.train }}"}}
        """));
    String element = "\"element\":{\"$ref\":\"device-actions.json#/schemas/locatorBundle\"}";
    harness.deviceInputSchemas.put("browser.clickItem", Json.read("{\"type\":\"object\","
        + "\"properties\":{" + element + ",\"item\":{\"type\":\"string\"}},"
        + "\"required\":[\"element\",\"item\"]}"));
    harness.toolRule = AutonomyRule.ALLOW;
    var items = Json.mapper().createArrayNode();
    items.addObject().put("label", "CDG MDU SF EXP").put("text", "CDG MDU SF EXP")
        .put("index", 0).put("shot", "11111111-1111-1111-1111-111111111111");
    items.addObject().put("label", "TEJAS EXPRESS").put("text", "TEJAS EXPRESS").put("index", 1);
    harness.deviceOutputs.put("browser.listItems", Json.obj().put("count", 2).set("items", items));

    var run = harness.engine.start(harness.workflow.getId(), 1,
        Json.obj().put("train", "Vande Bharat"), "manual");

    // The listing was asked to take the pictures too.
    assertThat(harness.deviceRequests.get(0).action()).isEqualTo("browser.listItems");
    assertThat(harness.deviceRequests.get(0).input().path("captureItems").asBoolean()).isTrue();
    // No train matches, so the person picks, with the pictures along for the ride.
    assertThat(run.status()).isEqualTo(RunStatus.PAUSED_WAITING_INSTRUCTION);
    JsonNode form = harness.raised.get(harness.raised.size() - 1).context().path("form");
    assertThat(form.path("shots").path("device").asText()).isNotEmpty();
    assertThat(form.path("shots").path("byOption").path("CDG MDU SF EXP").asText())
        .isEqualTo("11111111-1111-1111-1111-111111111111");
    assertThat(form.path("shots").path("byOption").has("TEJAS EXPRESS")).isFalse();
  }
}
