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
 * Dropdowns the site built, with items kept while recording (static) or read from the page on
 * each run (dynamic), and dates kept with the format the site writes them in.
 */
class DropdownAndDateStepsTest {

  private static WorkflowGraph oneStep(String config) {
    return new WorkflowGraph(List.of(
        new WorkflowNode("start", NodeType.TRIGGER, "start", null, null),
        new WorkflowNode("step", NodeType.DEVICE_ACTION, "step", Json.read(config),
            new WorkflowNode.RetryPolicy(1, 1, 1, 10)),
        new WorkflowNode("finish", NodeType.END, "finish", null, null)),
        List.of(new WorkflowEdge("start", "step", null), new WorkflowEdge("step", "finish", null)));
  }

  private static String select(String mode, String options) {
    return """
        {"preset":"web","action":"browser.selectOption","version":1,"target":"sameBrowser",
         "inputs":{"element":{"strategies":[{"by":"css","value":"#quota-box"}],"label":"Quota"},
                   "item":"{{ input.quota }}","mode":"%s"%s}}
        """.formatted(mode, options);
  }

  private static final String STATIC_OPTIONS =
      ",\"options\":[\"GENERAL\",\"LADIES\",\"TATKAL\",\"PREMIUM TATKAL\"]";

  private static EngineHarness harness(String config) {
    EngineHarness harness = new EngineHarness(oneStep(config));
    String element = "\"element\":{\"$ref\":\"device-actions.json#/schemas/locatorBundle\"}";
    harness.deviceInputSchemas.put("browser.selectOption", Json.read("{\"type\":\"object\","
        + "\"properties\":{" + element + ",\"item\":{\"type\":\"string\"},"
        + "\"options\":{\"type\":\"array\"},\"mode\":{\"enum\":[\"static\",\"dynamic\"]}},"
        + "\"required\":[\"element\",\"item\"]}"));
    harness.deviceInputSchemas.put("browser.setDate", Json.read("{\"type\":\"object\","
        + "\"properties\":{" + element + ",\"value\":{\"type\":\"string\"},"
        + "\"format\":{\"type\":\"string\"}},\"required\":[\"element\",\"value\"]}"));
    harness.deviceOutputs.put("browser.selectOption", Json.obj().put("selected", "x"));
    harness.deviceOutputs.put("browser.setDate", Json.obj().put("value", "x"));
    return harness;
  }

  private static JsonNode field(EngineHarness harness, String key) {
    JsonNode form = harness.raised.get(harness.raised.size() - 1).context().path("form");
    for (JsonNode f : form.path("fields")) if (key.equals(f.path("key").asText())) return f;
    return null;
  }

  private static List<String> actions(EngineHarness harness) {
    return harness.deviceRequests.stream().map(r -> r.action()).toList();
  }

  private static JsonNode last(EngineHarness harness) {
    return harness.deviceRequests.get(harness.deviceRequests.size() - 1).input();
  }

  @Test
  void aStaticDropdownChoosesFromTheItemsKeptWhileRecording() {
    EngineHarness harness = harness(select("static", STATIC_OPTIONS));
    harness.toolRule = AutonomyRule.ALLOW;

    var run = harness.engine.start(harness.workflow.getId(), 1,
        Json.obj().put("quota", "tatkal"), "manual");

    assertThat(run.status()).isEqualTo(RunStatus.DONE);
    // Nothing is read from the page: the items are the ones kept.
    assertThat(actions(harness)).containsExactly("browser.selectOption");
    assertThat(last(harness).path("item").asText()).isEqualTo("TATKAL");
  }

  @Test
  void aValueThatIsNotAStaticItemIsAskedWithTheItems() {
    EngineHarness harness = harness(select("static", STATIC_OPTIONS));
    harness.toolRule = AutonomyRule.ALLOW;

    var run = harness.engine.start(harness.workflow.getId(), 1,
        Json.obj().put("quota", "Senior citizen"), "manual");

    assertThat(run.status()).isEqualTo(RunStatus.PAUSED_WAITING_INSTRUCTION);
    JsonNode item = field(harness, "item");
    assertThat(item.path("label").asText()).isEqualTo("Quota");
    assertThat(item.path("type").asText()).isEqualTo("select");
    assertThat(item.path("options").size()).isEqualTo(4);
    assertThat(field(harness, "options")).isNull();
    assertThat(field(harness, "mode")).isNull();

    harness.engine.resume(run.id(), Json.obj().put("approved", true)
        .set("inputs", Json.obj().put("item", "LADIES")));
    assertThat(last(harness).path("item").asText()).isEqualTo("LADIES");
  }

  @Test
  void theCardOfAStaticDropdownOffersItsItems() {
    EngineHarness harness = harness(select("static", STATIC_OPTIONS));
    harness.toolRule = AutonomyRule.ASK;

    var run = harness.engine.start(harness.workflow.getId(), 1,
        Json.obj().put("quota", "GENERAL"), "manual");

    assertThat(run.status()).isEqualTo(RunStatus.PAUSED_WAITING_INSTRUCTION);
    JsonNode item = field(harness, "item");
    assertThat(item.path("type").asText()).isEqualTo("select");
    assertThat(item.path("value").asText()).isEqualTo("GENERAL");
    assertThat(item.path("options").toString())
        .isEqualTo("[\"GENERAL\",\"LADIES\",\"TATKAL\",\"PREMIUM TATKAL\"]");
  }

  @Test
  void aDynamicDropdownReadsItsItemsFromThePageOnEachRun() {
    EngineHarness harness = harness(select("dynamic", ""));
    harness.toolRule = AutonomyRule.ALLOW;
    var items = Json.mapper().createArrayNode();
    List.of("All Classes", "Sleeper (SL)", "AC 3 Tier (3A)").forEach(label ->
        items.addObject().put("label", label).put("text", label).put("index", items.size()));
    harness.deviceOutputs.put("browser.listOptions", Json.obj().put("count", 3).set("items", items));

    var run = harness.engine.start(harness.workflow.getId(), 1,
        Json.obj().put("quota", "3A"), "manual");

    assertThat(run.status()).isEqualTo(RunStatus.DONE);
    assertThat(actions(harness)).containsExactly("browser.listOptions", "browser.selectOption");
    assertThat(harness.deviceRequests.get(0).input().path("element").path("label").asText())
        .isEqualTo("Quota");
    assertThat(last(harness).path("item").asText()).isEqualTo("AC 3 Tier (3A)");
  }

  @Test
  void aDynamicDropdownWithNothingGivenAsksWithWhatThePageListsNow() {
    EngineHarness harness = harness(select("dynamic", ""));
    harness.toolRule = AutonomyRule.ALLOW;
    var items = Json.mapper().createArrayNode();
    items.addObject().put("label", "GENERAL").put("text", "GENERAL").put("index", 0);
    items.addObject().put("label", "TATKAL").put("text", "TATKAL").put("index", 1);
    harness.deviceOutputs.put("browser.listOptions", Json.obj().put("count", 2).set("items", items));

    var run = harness.engine.start(harness.workflow.getId(), 1, Json.obj(), "manual");

    assertThat(run.status()).isEqualTo(RunStatus.PAUSED_WAITING_INSTRUCTION);
    assertThat(field(harness, "item").path("options").toString())
        .isEqualTo("[\"GENERAL\",\"TATKAL\"]");
  }

  @Test
  void aDateFieldCarriesTheSitesFormatAndATimeMakesItADateAndTime() {
    EngineHarness harness = harness("""
        {"preset":"web","action":"browser.setDate","version":1,"target":"sameBrowser",
         "inputs":{"element":{"strategies":[{"by":"css","value":"#d"}],"label":"Journey date"},
                   "value":"{{ input.when }}","format":"DD/MM/YYYY"}}
        """);
    harness.toolRule = AutonomyRule.ASK;

    var run = harness.engine.start(harness.workflow.getId(), 1,
        Json.obj().put("when", "2026-11-20"), "manual");

    assertThat(run.status()).isEqualTo(RunStatus.PAUSED_WAITING_INSTRUCTION);
    JsonNode value = field(harness, "value");
    assertThat(value.path("type").asText()).isEqualTo("date");
    assertThat(value.path("format").asText()).isEqualTo("DD/MM/YYYY");
    assertThat(field(harness, "format")).isNull();

    EngineHarness timed = harness("""
        {"preset":"web","action":"browser.setDate","version":1,"target":"sameBrowser",
         "inputs":{"element":{"strategies":[{"by":"css","value":"#d"}],"label":"Departs"},
                   "value":"{{ input.when }}","format":"DD/MM/YYYY hh:mm A"}}
        """);
    timed.toolRule = AutonomyRule.ASK;
    timed.engine.start(timed.workflow.getId(), 1, Json.obj().put("when", "2026-11-20T18:30"),
        "manual");
    assertThat(field(timed, "value").path("type").asText()).isEqualTo("datetime");
    assertThat(field(timed, "value").path("format").asText()).isEqualTo("DD/MM/YYYY hh:mm A");
  }
}
