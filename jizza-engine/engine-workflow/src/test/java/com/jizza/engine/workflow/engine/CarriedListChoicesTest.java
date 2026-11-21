package com.jizza.engine.workflow.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.enums.AutonomyRule;
import com.jizza.engine.api.enums.NodeType;
import com.jizza.engine.api.enums.RunStatus;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.workflow.graph.WorkflowEdge;
import com.jizza.engine.api.workflow.graph.WorkflowGraph;
import com.jizza.engine.api.workflow.graph.WorkflowNode;
import java.util.ArrayDeque;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A card of a list chosen in one step (the train, chosen with its coach) is carried on to later
 * steps that choose inside the same card (the date in that train), which show it as chosen earlier
 * instead of asking for it again.
 */
class CarriedListChoicesTest {

  private static final String TRAIN = """
      "within":{"element":{"strategies":[{"by":"css","value":".train"}],"label":"Train card"},
                "item":"{{ input.trains }}"}""";

  private static String coaches() {
    return """
        {"preset":"web","action":"browser.clickItem","version":1,"target":"sameBrowser",
         "list":{"title":"Coaches","withinTitle":"Trains","named":true},
         "inputs":{"element":{"strategies":[{"by":"css","value":"td.class"}],"label":"Coach"},
                   "item":"{{ input.coaches }}",%s}}
        """.formatted(TRAIN);
  }

  private static String dates(String dependsOn) {
    return """
        {"preset":"web","action":"browser.clickItem","version":1,"target":"sameBrowser",
         "list":{"title":"Dates","withinTitle":"Trains","named":true%s},
         "inputs":{"element":{"strategies":[{"by":"css","value":"td.date"}],"label":"Date"},
                   "item":"{{ input.dates }}",%s}}
        """.formatted(dependsOn, TRAIN);
  }

  private static WorkflowGraph twoSteps(String first, String second) {
    return new WorkflowGraph(List.of(
        new WorkflowNode("start", NodeType.TRIGGER, "start", null, null),
        new WorkflowNode("coach", NodeType.DEVICE_ACTION, "coach", Json.read(first),
            new WorkflowNode.RetryPolicy(1, 1, 1, 10)),
        new WorkflowNode("date", NodeType.DEVICE_ACTION, "date", Json.read(second),
            new WorkflowNode.RetryPolicy(1, 1, 1, 10)),
        new WorkflowNode("finish", NodeType.END, "finish", null, null)),
        List.of(new WorkflowEdge("start", "coach", null), new WorkflowEdge("coach", "date", null),
            new WorkflowEdge("date", "finish", null)));
  }

  private static ObjectNode listing(String... labels) {
    var items = Json.mapper().createArrayNode();
    for (int i = 0; i < labels.length; i++) {
      items.addObject().put("index", i).put("label", labels[i]).put("text", labels[i]);
    }
    return (ObjectNode) Json.obj().put("count", labels.length).set("items", items);
  }

  private static EngineHarness harness(WorkflowGraph graph) {
    EngineHarness harness = new EngineHarness(graph);
    String element = "\"element\":{\"$ref\":\"device-actions.json#/schemas/locatorBundle\"}";
    harness.deviceInputSchemas.put("browser.clickItem", Json.read("{\"type\":\"object\","
        + "\"properties\":{" + element + ",\"item\":{\"type\":\"string\"},"
        + "\"by\":{\"enum\":[\"text\",\"index\"]},\"within\":{\"type\":\"object\"}},"
        + "\"required\":[\"element\"]}"));
    harness.deviceOutputs.put("browser.clickItem", Json.obj().put("clicked", true));
    harness.toolRule = AutonomyRule.ASK;
    return harness;
  }

  private static JsonNode form(EngineHarness harness) {
    return harness.raised.get(harness.raised.size() - 1).context().path("form");
  }

  private static JsonNode field(EngineHarness harness, String key) {
    for (JsonNode f : form(harness).path("fields")) if (key.equals(f.path("key").asText())) return f;
    return null;
  }

  /** The first step asks for the train and its coach; the person picks Tejas, Exec. Chair Car. */
  private static java.util.UUID chooseTrainAndCoach(EngineHarness harness, ObjectNode... later) {
    var trains = listing("MS NCJ VB EXP (20627)", "TEJAS EXPRESS (22671)");
    var first = listing("AC Chair car (CC)", "Exec. Chair Car (EC)");
    var groups = first.putArray("groups");
    groups.addObject().put("index", 0).put("label", "MS NCJ VB EXP (20627)")
        .set("items", listing("AC Chair car (CC)", "Exec. Chair Car (EC)").get("items"));
    groups.addObject().put("index", 1).put("label", "TEJAS EXPRESS (22671)")
        .set("items", listing("AC Chair car (CC)", "Exec. Chair Car (EC)").get("items"));
    var queue = new ArrayDeque<JsonNode>(List.of(trains, first, trains,
        listing("AC Chair car (CC)", "Exec. Chair Car (EC)")));
    queue.addAll(List.of(later));
    harness.deviceOutputQueues.put("browser.listItems", queue);

    var run = harness.engine.start(harness.workflow.getId(), 1, Json.obj(), "manual");
    assertThat(run.status()).isEqualTo(RunStatus.PAUSED_WAITING_INSTRUCTION);
    assertThat(field(harness, "within.item")).isNotNull();
    harness.engine.resume(run.id(), Json.obj().put("approved", true).set("inputs",
        Json.obj().put("within.item", "TEJAS EXPRESS (22671)").put("item", "Exec. Chair Car (EC)")));
    return run.id();
  }

  @Test
  void theTrainChosenWithTheCoachIsNotAskedForAgainWithTheDates() {
    EngineHarness harness = harness(twoSteps(coaches(), dates("")));
    var trains = listing("MS NCJ VB EXP (20627)", "TEJAS EXPRESS (22671)");
    var runId = chooseTrainAndCoach(harness, trains, listing("Fri, 27 Nov", "Sat, 28 Nov"));

    // The date step stops for the date only; the train is read as chosen earlier.
    assertThat(harness.raised).hasSize(2);
    assertThat(field(harness, "within.item")).isNull();
    JsonNode date = field(harness, "item");
    assertThat(date.path("options").toString()).isEqualTo("[\"Fri, 27 Nov\",\"Sat, 28 Nov\"]");
    JsonNode carried = form(harness).path("carried");
    assertThat(carried.toString()).isEqualTo("[{\"label\":\"Trains\",\"value\":\"TEJAS EXPRESS (22671)\"},"
        + "{\"label\":\"Coaches\",\"value\":\"Exec. Chair Car (EC)\"}]");
    // The dates were read inside the train chosen in the first step.
    var lists = harness.deviceRequests.stream()
        .filter(r -> r.action().equals("browser.listItems")).toList();
    assertThat(lists.get(lists.size() - 1).input().path("within").path("item").asText())
        .isEqualTo("TEJAS EXPRESS (22671)");

    harness.deviceOutputQueues.put("browser.listItems", new ArrayDeque<>(List.of(trains,
        listing("Fri, 27 Nov", "Sat, 28 Nov"))));
    harness.engine.resume(runId, Json.obj().put("approved", true).set("inputs",
        Json.obj().put("item", "Sat, 28 Nov")));
    var sent = harness.deviceRequests.get(harness.deviceRequests.size() - 1);
    assertThat(sent.action()).isEqualTo("browser.clickItem");
    assertThat(sent.input().path("within").path("item").asText()).isEqualTo("TEJAS EXPRESS (22671)");
    assertThat(sent.input().path("item").asText()).isEqualTo("Sat, 28 Nov");
  }

  @Test
  void aListThatFollowsAnotherShowsThatChoiceAndItsCard() {
    // Dates load after Coaches and are not chosen inside a card of their own.
    String date = """
        {"preset":"web","action":"browser.clickItem","version":1,"target":"sameBrowser",
         "list":{"title":"Dates","named":true,
                 "dependsOn":{"title":"Coaches","inputName":"coaches","relation":"after"}},
         "inputs":{"element":{"strategies":[{"by":"css","value":"td.date"}],"label":"Date"},
                   "item":"{{ input.dates }}"}}
        """;
    EngineHarness harness = harness(twoSteps(coaches(), date));
    chooseTrainAndCoach(harness, listing("Fri, 27 Nov", "Sat, 28 Nov"));

    assertThat(harness.raised).hasSize(2);
    assertThat(form(harness).path("carried").toString())
        .isEqualTo("[{\"label\":\"Trains\",\"value\":\"TEJAS EXPRESS (22671)\"},"
            + "{\"label\":\"Coaches\",\"value\":\"Exec. Chair Car (EC)\"}]");
  }

  @Test
  void aTrainGivenToTheRunIsUsedAsItIs() {
    EngineHarness harness = harness(twoSteps(coaches(), dates("")));
    harness.toolRule = AutonomyRule.ALLOW;
    var trains = listing("MS NCJ VB EXP (20627)", "TEJAS EXPRESS (22671)");
    harness.deviceOutputQueues.put("browser.listItems", new ArrayDeque<>(List.of(
        trains, listing("AC Chair car (CC)", "Exec. Chair Car (EC)"),
        trains, listing("Fri, 27 Nov", "Sat, 28 Nov"))));

    var run = harness.engine.start(harness.workflow.getId(), 1, Json.obj()
        .put("trains", "MS NCJ VB EXP (20627)").put("coaches", "AC Chair car (CC)")
        .put("dates", "Fri, 27 Nov"), "manual");

    assertThat(run.status()).isEqualTo(RunStatus.DONE);
    assertThat(harness.raised).isEmpty();
  }

  @Test
  void aChainOfListsIsShownOutermostFirst() {
    // Trains, then Coaches that load after Trains, then Dates that load after Coaches.
    String step = """
        {"preset":"web","action":"browser.clickItem","version":1,"target":"sameBrowser",
         "list":{"title":"%s","named":true%s},
         "inputs":{"element":{"strategies":[{"by":"css","value":"td"}],"label":"x"},
                   "item":"{{ input.%s }}"}}
        """;
    String after = ",\"dependsOn\":{\"title\":\"%s\",\"relation\":\"after\"}";
    WorkflowGraph graph = new WorkflowGraph(List.of(
        new WorkflowNode("start", NodeType.TRIGGER, "start", null, null),
        new WorkflowNode("train", NodeType.DEVICE_ACTION, "train",
            Json.read(step.formatted("Trains", "", "trains")), null),
        new WorkflowNode("coach", NodeType.DEVICE_ACTION, "coach",
            Json.read(step.formatted("Coaches", after.formatted("Trains"), "coaches")), null),
        new WorkflowNode("date", NodeType.DEVICE_ACTION, "date",
            Json.read(step.formatted("Dates", after.formatted("Coaches"), "dates")), null),
        new WorkflowNode("finish", NodeType.END, "finish", null, null)),
        List.of(new WorkflowEdge("start", "train", null), new WorkflowEdge("train", "coach", null),
            new WorkflowEdge("coach", "date", null), new WorkflowEdge("date", "finish", null)));
    EngineHarness harness = harness(graph);
    var trains = listing("MS NCJ VB EXP (20627)", "TEJAS EXPRESS (22671)");
    var coaches = listing("AC Chair car (CC)", "Exec. Chair Car (EC)");
    var dates = listing("Fri, 27 Nov", "Sat, 28 Nov");
    harness.deviceOutputQueues.put("browser.listItems",
        new ArrayDeque<>(List.of(trains, trains, coaches, coaches, dates)));

    var run = harness.engine.start(harness.workflow.getId(), 1, Json.obj(), "manual");
    harness.engine.resume(run.id(), Json.obj().put("approved", true).set("inputs",
        Json.obj().put("item", "TEJAS EXPRESS (22671)")));
    assertThat(form(harness).path("carried").toString())
        .isEqualTo("[{\"label\":\"Trains\",\"value\":\"TEJAS EXPRESS (22671)\"}]");
    harness.engine.resume(run.id(), Json.obj().put("approved", true).set("inputs",
        Json.obj().put("item", "Exec. Chair Car (EC)")));

    assertThat(harness.raised).hasSize(3);
    assertThat(form(harness).path("carried").toString())
        .isEqualTo("[{\"label\":\"Trains\",\"value\":\"TEJAS EXPRESS (22671)\"},"
            + "{\"label\":\"Coaches\",\"value\":\"Exec. Chair Car (EC)\"}]");
  }
}
