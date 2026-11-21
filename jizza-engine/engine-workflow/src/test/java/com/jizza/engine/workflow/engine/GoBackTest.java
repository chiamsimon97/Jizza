package com.jizza.engine.workflow.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.enums.AutonomyRule;
import com.jizza.engine.api.enums.NodeType;
import com.jizza.engine.api.enums.RunStatus;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.workflow.graph.WorkflowEdge;
import com.jizza.engine.api.workflow.graph.WorkflowGraph;
import com.jizza.engine.api.workflow.graph.WorkflowNode;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Going back from a step's question to an earlier step: choose a train, then a coach, and when
 * the next step shows nothing available, go back and choose again.
 */
class GoBackTest {

  private static final String TRAIN = """
      {"preset":"web","action":"browser.clickItem","version":1,"target":"sameBrowser",
       "inputs":{"element":{"strategies":[{"by":"css","value":".train"}]},"item":"{{ input.train }}"}}
      """;
  private static final String COACH = """
      {"preset":"web","action":"browser.clickItem","version":1,"target":"sameBrowser",
       "inputs":{"element":{"strategies":[{"by":"css","value":".coach"}]},"item":"{{ input.coach }}"}}
      """;

  private static String check(String extra) {
    return """
        {"preset":"web","action":"browser.setText","version":1,"target":"sameBrowser",
         "inputs":{"element":{"strategies":[{"by":"css","value":"#date"}]},"text":"21 Nov"}
        """ + extra + "}";
  }

  private static EngineHarness harness(String checkConfig) {
    WorkflowGraph graph = new WorkflowGraph(List.of(
        new WorkflowNode("start", NodeType.TRIGGER, "start", null, null),
        node("train", "Choose train", TRAIN),
        node("coach", "Choose coach", COACH),
        node("check", "Check availability", checkConfig),
        new WorkflowNode("finish", NodeType.END, "finish", null, null)),
        List.of(new WorkflowEdge("start", "train", null), new WorkflowEdge("train", "coach", null),
            new WorkflowEdge("coach", "check", null), new WorkflowEdge("check", "finish", null)));
    EngineHarness harness = new EngineHarness(graph);
    String element = "\"element\":{\"$ref\":\"device-actions.json#/schemas/locatorBundle\"}";
    harness.deviceInputSchemas.put("browser.setText", Json.read("{\"type\":\"object\","
        + "\"properties\":{" + element + ",\"text\":{\"type\":\"string\"}},"
        + "\"required\":[\"element\",\"text\"]}"));
    harness.deviceInputSchemas.put("browser.clickItem", Json.read("{\"type\":\"object\","
        + "\"properties\":{" + element + ",\"item\":{\"type\":\"string\"},"
        + "\"by\":{\"enum\":[\"text\",\"index\"]}},\"required\":[\"element\",\"item\"]}"));
    harness.deviceOutputs.put("browser.clickItem", Json.obj().put("clicked", true));
    harness.deviceOutputs.put("browser.setText", Json.obj().put("ok", true));
    harness.deviceOutputs.put("browser.back", Json.obj().put("moved", true));
    return harness;
  }

  private static WorkflowNode node(String id, String label, String config) {
    return new WorkflowNode(id, NodeType.DEVICE_ACTION, label, Json.read(config),
        new WorkflowNode.RetryPolicy(1, 1, 1, 10));
  }

  private static JsonNode listing(String... labels) {
    var items = Json.mapper().createArrayNode();
    for (int i = 0; i < labels.length; i++) {
      items.addObject().put("index", i).put("label", labels[i]).put("text", labels[i]);
    }
    return Json.obj().put("count", labels.length).set("items", items);
  }

  private static JsonNode lastQuestion(EngineHarness harness) {
    return harness.raised.get(harness.raised.size() - 1).context();
  }

  private static final JsonNode TRAINS = listing("12951 Rajdhani", "12953 Kranti");
  private static final JsonNode COACHES = listing("1A", "2A", "3A");

  /**
   * Runs to the availability step's question, the train and coach chosen on their cards. Each
   * choosing step reads its list, asks, and reads it again after the answer; {@code then} are the
   * lists read after that.
   */
  private static java.util.UUID toTheCheck(EngineHarness harness, JsonNode... then) {
    harness.toolRule = AutonomyRule.ASK;
    var queue = new java.util.ArrayDeque<JsonNode>(List.of(TRAINS, TRAINS, COACHES, COACHES));
    queue.addAll(List.of(then));
    harness.deviceOutputQueues.put("browser.listItems", queue);
    var run = harness.engine.start(harness.workflow.getId(), 1,
        Json.obj().put("train", "Rajdhani").put("coach", "3A"), "manual");
    assertThat(lastQuestion(harness).path("node").asText()).isEqualTo("train");
    harness.engine.resume(run.id(), Json.obj().put("approved", true));
    assertThat(lastQuestion(harness).path("node").asText()).isEqualTo("coach");
    harness.engine.resume(run.id(), Json.obj().put("approved", true));
    assertThat(lastQuestion(harness).path("node").asText()).isEqualTo("check");
    return run.id();
  }

  @Test
  void backOffersTheStepsThatAskedMostRecentFirstAndChoosesAgainThere() {
    EngineHarness harness = harness(check(",\"allowBack\":true"));
    var runId = toTheCheck(harness, COACHES, COACHES);

    JsonNode back = lastQuestion(harness).path("back");
    assertThat(back).hasSize(2);
    assertThat(back.get(0).path("node").asText()).isEqualTo("coach");
    assertThat(back.get(0).path("label").asText()).isEqualTo("Choose coach");
    assertThat(back.get(1).path("node").asText()).isEqualTo("train");

    int sentBefore = harness.deviceRequests.size();
    var run = harness.engine.resume(runId, Json.obj().put("back", ""));

    // The coach step reads the page's list again and asks, even though 3A is listed.
    assertThat(run.status()).isEqualTo(RunStatus.PAUSED_WAITING_INSTRUCTION);
    assertThat(lastQuestion(harness).path("node").asText()).isEqualTo("coach");
    assertThat(harness.raised.get(harness.raised.size() - 1).body())
        .contains("You went back to choose again");
    assertThat(harness.deviceRequests.subList(sentBefore, harness.deviceRequests.size()))
        .extracting(r -> r.action()).containsExactly("browser.listItems");
    assertThat(harness.steps.findByRunIdAndNodeId(runId, "check")).isEmpty();
    assertThat(harness.steps.findByRunIdAndNodeId(runId, "coach")).isEmpty();
    assertThat(harness.steps.findByRunIdAndNodeId(runId, "train")).isPresent();

    // A different coach this time, then the check runs again and the run finishes.
    harness.engine.resume(runId, Json.obj().put("approved", true)
        .set("inputs", Json.obj().put("item", "2A")));
    var clicks = harness.deviceRequests.stream()
        .filter(r -> r.action().equals("browser.clickItem")).toList();
    assertThat(clicks.get(clicks.size() - 1).input().path("item").asText()).isEqualTo("2A");
    // Sent again for real, not answered from the first time's command.
    assertThat(clicks.get(clicks.size() - 1).idempotencyKey())
        .isNotEqualTo(clicks.get(1).idempotencyKey());
    assertThat(lastQuestion(harness).path("node").asText()).isEqualTo("check");
    var done = harness.engine.resume(runId, Json.obj().put("approved", true));
    assertThat(done.status()).isEqualTo(RunStatus.DONE);
  }

  @Test
  void backCanGoFurtherToAnEarlierStepAndGoesBackPagesInTheBrowserFirst() {
    EngineHarness harness = harness(check(",\"allowBack\":true,\"backPages\":2"));
    var runId = toTheCheck(harness, TRAINS, TRAINS, COACHES);

    int sentBefore = harness.deviceRequests.size();
    harness.engine.resume(runId, Json.obj().put("back", "train").put("node", "check"));

    assertThat(harness.deviceRequests.subList(sentBefore, harness.deviceRequests.size()))
        .extracting(r -> r.action())
        .containsExactly("browser.back", "browser.back", "browser.listItems");
    assertThat(lastQuestion(harness).path("node").asText()).isEqualTo("train");
    // The coach is chosen again after the train, as it was the first time.
    harness.engine.resume(runId, Json.obj().put("approved", true));
    assertThat(lastQuestion(harness).path("node").asText()).isEqualTo("coach");
  }

  @Test
  void aNamedStepIsTheOnlyOneOffered() {
    EngineHarness harness = harness(check(",\"allowBack\":true,\"backTo\":\"train\""));
    var runId = toTheCheck(harness);

    JsonNode back = lastQuestion(harness).path("back");
    assertThat(back).hasSize(1);
    assertThat(back.get(0).path("node").asText()).isEqualTo("train");
    assertThatThrownBy(() -> harness.engine.resume(runId, Json.obj().put("back", "coach")))
        .isInstanceOf(ValidationException.class).hasMessageContaining("Choose train");
  }

  @Test
  void aStepThatDoesNotAllowGoingBackOffersNoBack() {
    EngineHarness harness = harness(check(""));
    var runId = toTheCheck(harness);

    assertThat(lastQuestion(harness).has("back")).isFalse();
    assertThatThrownBy(() -> harness.engine.resume(runId, Json.obj().put("back", "")))
        .isInstanceOf(ValidationException.class).hasMessageContaining("Allow going back");
  }
}
