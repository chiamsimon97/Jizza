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
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A device layer ({@code browser.fillLayer}) fills a whole area of a page and clicks one of its
 * buttons; the run goes on along that button's path.
 */
class DeviceLayerTest {

  private static final String LAYER = """
      {"preset":"web","action":"browser.fillLayer","version":1,"target":"sameBrowser",
       "inputs":{
         "groups":[{"id":"g1","title":"Task details"}],
         "fields":[
           {"id":"f1","label":"Stage","kind":"select","section":"static","value":"Open",
            "element":{"strategies":[{"by":"css","value":"#stage"}]}},
           {"id":"f2","label":"Project","kind":"select","section":"config","group":"g1",
            "value":"{{ input.project }}","required":true,
            "element":{"strategies":[{"by":"css","value":"#project"}]}},
           {"id":"f3","label":"Milestone","kind":"select","section":"config","group":"g1",
            "dependsOn":"f2","mode":"dynamic","required":true,
            "element":{"strategies":[{"by":"css","value":"#milestone"}]}},
           {"id":"f4","label":"Title","kind":"text","section":"config","group":"g1",
            "value":"{{ input.title }}",
            "element":{"strategies":[{"by":"css","value":"#title"}]}}],
         "buttons":[
           {"id":"b1","name":"Submit","kind":"submit","element":{"strategies":[{"by":"text","value":"Submit"}]}},
           {"id":"b2","name":"Cancel","kind":"cancel","element":{"strategies":[{"by":"text","value":"Cancel"}]}}],
         "button":"%s"}}
      """;

  private static WorkflowNode node(String id, NodeType type, String config) {
    return new WorkflowNode(id, type, id, config == null ? null : Json.read(config),
        new WorkflowNode.RetryPolicy(1, 1, 1, 10));
  }

  private static WorkflowNode web(String id, String action) {
    return node(id, NodeType.DEVICE_ACTION, """
        {"preset":"web","action":"%s","version":1,"target":"sameBrowser",
         "inputs":{"element":{"strategies":[{"by":"text","value":"%s"}]}}}
        """.formatted(action, id));
  }

  /** start → layer → (Submit) saved → finish, (Cancel) dropped → finish. */
  private static WorkflowGraph graph(String button, boolean cancelPath) {
    List<WorkflowNode> nodes = List.of(node("start", NodeType.TRIGGER, null),
        node("layer", NodeType.DEVICE_ACTION, LAYER.formatted(button)),
        web("saved", "browser.getText"), web("dropped", "browser.click"),
        node("finish", NodeType.END, null));
    List<WorkflowEdge> edges = new ArrayList<>(List.of(
        new WorkflowEdge("start", "layer", null), new WorkflowEdge("layer", "saved", "b1"),
        new WorkflowEdge("saved", "finish", null), new WorkflowEdge("dropped", "finish", null)));
    if (cancelPath) edges.add(new WorkflowEdge("layer", "dropped", "b2"));
    return new WorkflowGraph(nodes, edges);
  }

  private static EngineHarness harness(String button, boolean cancelPath) {
    EngineHarness h = new EngineHarness(graph(button, cancelPath));
    h.toolRule = AutonomyRule.ALLOW;
    h.deviceOutputs.put("browser.getText", Json.obj().put("text", "ok"));
    h.deviceOutputs.put("browser.click", Json.obj().put("clicked", true));
    return h;
  }

  private static List<String> actions(EngineHarness h) {
    return h.deviceRequests.stream().map(r -> r.action()).toList();
  }

  private static JsonNode lastForm(EngineHarness h) {
    return h.raised.get(h.raised.size() - 1).context().path("form");
  }

  private static JsonNode field(EngineHarness h, String key) {
    for (JsonNode f : lastForm(h).path("fields")) if (key.equals(f.path("key").asText())) return f;
    return null;
  }

  @Test
  void theClickedButtonsPathIsTaken() {
    EngineHarness h = harness("Cancel", true);
    h.deviceOutputs.put("browser.fillLayer", Json.obj().put("clicked", "b2"));
    var run = h.engine.start(h.workflow.getId(), 1,
        Json.obj().put("project", "Jizza").put("title", "Write plan"), "manual");
    assertThat(run.status()).isEqualTo(RunStatus.DONE);
    assertThat(actions(h)).containsExactly("browser.fillLayer", "browser.click");
    // The button named by its name goes to the device by its id, values resolved.
    JsonNode sent = h.deviceRequests.get(0).input();
    assertThat(sent.path("button").asText()).isEqualTo("b2");
    assertThat(sent.path("fields").get(1).path("value").asText()).isEqualTo("Jizza");
    assertThat(sent.path("fields").size()).isEqualTo(4);
  }

  @Test
  void aButtonWithNoPathEndsThere() {
    EngineHarness h = harness("b2", false);
    h.deviceOutputs.put("browser.fillLayer", Json.obj().put("clicked", "b2"));
    var run = h.engine.start(h.workflow.getId(), 1,
        Json.obj().put("project", "Jizza").put("title", "Write plan"), "manual");
    assertThat(run.status()).isEqualTo(RunStatus.DONE);
    assertThat(actions(h)).containsExactly("browser.fillLayer");
  }

  @Test
  void withNoButtonTheCardAsksWhichWithTheLayersButtons() {
    EngineHarness h = harness("", true);
    h.deviceOutputs.put("browser.fillLayer", Json.obj().put("clicked", "b1"));
    java.util.UUID browser = java.util.UUID.randomUUID();
    var run = h.engine.start(h.workflow.getId(), 1,
        Json.obj().put("project", "Jizza").put("title", "Write plan"), "manual", null, browser);
    assertThat(run.status()).isEqualTo(RunStatus.PAUSED_WAITING_INSTRUCTION);
    assertThat(h.deviceRequests).isEmpty();
    JsonNode button = field(h, "button");
    assertThat(button.path("missing").asBoolean()).isTrue();
    assertThat(button.path("options").toString()).isEqualTo("[\"Submit\",\"Cancel\"]");
    // Fixed values are listed, not asked; values for this run sit under their group.
    assertThat(lastForm(h).path("fixed").get(0).path("label").asText()).isEqualTo("Stage");
    assertThat(field(h, "fields.0.value")).isNull();
    assertThat(field(h, "fields.1.value").path("group").asText()).isEqualTo("Task details");
    // Milestone loads after Project: not missing yet, the page lists its choices later.
    assertThat(field(h, "fields.2.value").path("missing").asBoolean()).isFalse();
    // Refresh: each value for this run that the page can list again, and how to read it.
    JsonNode live = lastForm(h).path("live");
    assertThat(live.path("device").asText()).isEqualTo(browser.toString());
    assertThat(live.path("fields").path("fields.1.value").path("action").asText())
        .isEqualTo("browser.listOptions");
    assertThat(live.path("fields").path("fields.3.value").path("action").asText())
        .isEqualTo("browser.suggestOptions");
    assertThat(live.path("fields").path("fields.3.value").path("typed").asBoolean()).isTrue();
    assertThat(live.path("fields").has("fields.0.value")).isFalse();

    h.engine.resume(run.id(), Json.obj().put("approved", true)
        .set("inputs", Json.obj().put("button", "Submit")));
    assertThat(actions(h)).containsExactly("browser.fillLayer", "browser.getText");
    assertThat(h.deviceRequests.get(0).input().path("button").asText()).isEqualTo("b1");
  }

  @Test
  void aValueThePageListsLaterIsAskedThenTheLayerCarriesOnFromIt() {
    EngineHarness h = harness("Submit", true);
    var outputs = new java.util.ArrayDeque<JsonNode>();
    JsonNode needs = Json.read("""
        {"needs":{"field":"f3","label":"Milestone","options":["M1","M2"]},
         "filled":[{"id":"f1"},{"id":"f2"}]}""");
    outputs.add(needs);
    outputs.add(Json.obj().put("clicked", "b1"));
    h.deviceOutputQueues.put("browser.fillLayer", outputs);
    var run = h.engine.start(h.workflow.getId(), 1,
        Json.obj().put("project", "Jizza").put("title", "Write plan"), "manual");
    assertThat(run.status()).isEqualTo(RunStatus.PAUSED_WAITING_INSTRUCTION);
    JsonNode milestone = field(h, "fields.2.value");
    assertThat(milestone.path("type").asText()).isEqualTo("select");
    assertThat(milestone.path("options").toString()).isEqualTo("[\"M1\",\"M2\"]");
    assertThat(milestone.path("missing").asBoolean()).isTrue();

    h.engine.resume(run.id(), Json.obj().put("approved", true)
        .set("inputs", Json.obj().put("fields.2.value", "M2").put("button", "Submit")));
    assertThat(actions(h)).containsExactly("browser.fillLayer", "browser.fillLayer",
        "browser.getText");
    JsonNode again = h.deviceRequests.get(1).input();
    assertThat(again.path("startAt").asText()).isEqualTo("f3");
    assertThat(again.path("fields").get(2).path("value").asText()).isEqualTo("M2");
  }

  @Test
  void pathsMustBelongToButtons() {
    List<WorkflowNode> nodes = List.of(node("start", NodeType.TRIGGER, null),
        node("layer", NodeType.DEVICE_ACTION, LAYER.formatted("b1")),
        web("saved", "browser.getText"), node("finish", NodeType.END, null));
    WorkflowGraph bad = new WorkflowGraph(nodes, List.of(new WorkflowEdge("start", "layer", null),
        new WorkflowEdge("layer", "saved", "b9"), new WorkflowEdge("saved", "finish", null)));
    EngineHarness h = new EngineHarness(bad);
    assertThatThrownBy(() -> h.engine.start(h.workflow.getId(), 1, Json.obj(), "manual"))
        .isInstanceOf(ValidationException.class).hasMessageContaining("belongs to no button");
  }
}
