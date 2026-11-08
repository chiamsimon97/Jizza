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
 * Web steps whose values come from the run: an approval card that shows them resolved and takes
 * the person's changes, mandatory values that resolved empty, and a list item chosen at run time.
 */
class DynamicWebStepsTest {

  private static WorkflowGraph oneStep(String config) {
    return new WorkflowGraph(List.of(
        new WorkflowNode("start", NodeType.TRIGGER, "start", null, null),
        new WorkflowNode("step", NodeType.DEVICE_ACTION, "step", Json.read(config),
            new WorkflowNode.RetryPolicy(1, 1, 1, 10)),
        new WorkflowNode("finish", NodeType.END, "finish", null, null)),
        List.of(new WorkflowEdge("start", "step", null), new WorkflowEdge("step", "finish", null)));
  }

  private static final String SET_TEXT = """
      {"preset":"web","action":"browser.setText","version":1,"target":"sameBrowser",
       "inputs":{"element":{"strategies":[{"by":"css","value":"#name"}]},
                 "text":"{{ input.name }}"}}
      """;

  private static final String CLICK_MOVIE = """
      {"preset":"web","action":"browser.clickItem","version":1,"target":"sameBrowser",
       "inputs":{"element":{"strategies":[{"by":"text","value":"Ramayana"}],"label":"Ramayana"},
                 "item":"{{ input.movie }}"}}
      """;

  private static JsonNode listing(String... labels) {
    var items = Json.mapper().createArrayNode();
    for (int i = 0; i < labels.length; i++) {
      items.addObject().put("index", i).put("label", labels[i]).put("text", labels[i]);
    }
    return Json.obj().put("count", labels.length).set("items", items);
  }

  private static EngineHarness harness(String config) {
    EngineHarness harness = new EngineHarness(oneStep(config));
    String element = "\"element\":{\"$ref\":\"device-actions.json#/schemas/locatorBundle\"}";
    harness.deviceInputSchemas.put("browser.setText", Json.read("{\"type\":\"object\","
        + "\"properties\":{" + element + ",\"text\":{\"type\":\"string\"},\"secretRef\":{}},"
        + "\"required\":[\"element\"],\"oneOf\":[{\"required\":[\"text\"]},"
        + "{\"required\":[\"secretRef\"]}]}"));
    harness.deviceInputSchemas.put("browser.clickItem", Json.read("{\"type\":\"object\","
        + "\"properties\":{" + element + ",\"item\":{\"type\":\"string\"},"
        + "\"by\":{\"enum\":[\"text\",\"index\"]}},\"required\":[\"element\",\"item\"]}"));
    harness.deviceInputSchemas.put("browser.fillForm", Json.read("{\"type\":\"object\","
        + "\"properties\":{\"fields\":{\"type\":\"array\"}},\"required\":[\"fields\"]}"));
    return harness;
  }

  private static JsonNode form(EngineHarness harness) {
    return harness.raised.get(harness.raised.size() - 1).context().path("form");
  }

  @Test
  void askMeShowsTheResolvedValueAndTheApprovedChangeIsWhatIsSent() {
    EngineHarness harness = harness(SET_TEXT);
    harness.toolRule = AutonomyRule.ASK;

    var run = harness.engine.start(harness.workflow.getId(), 1,
        Json.obj().put("name", "Asha"), "manual");

    assertThat(run.status()).isEqualTo(RunStatus.PAUSED_WAITING_INSTRUCTION);
    JsonNode field = form(harness).path("fields").get(0);
    assertThat(field.path("key").asText()).isEqualTo("text");
    assertThat(field.path("value").asText()).isEqualTo("Asha");
    assertThat(field.path("source").asText()).isEqualTo("{{ input.name }}");
    assertThat(form(harness).path("data").toString()).contains("input.name");

    harness.engine.resume(run.id(), Json.obj().put("approved", true)
        .set("inputs", Json.obj().put("text", "Asha Rao").put("element", "{\"x\":1}")));

    var sent = harness.deviceRequests.get(harness.deviceRequests.size() - 1);
    assertThat(sent.action()).isEqualTo("browser.setText");
    assertThat(sent.input().path("text").asText()).isEqualTo("Asha Rao");
    // Only the fields the card offered can change; the element stays as the step has it.
    assertThat(sent.input().path("element").path("strategies").isArray()).isTrue();
  }

  @Test
  void aMandatoryValueThatResolvedEmptyAsksEvenWhenAllowed() {
    EngineHarness harness = harness(SET_TEXT);
    harness.toolRule = AutonomyRule.ALLOW;

    var run = harness.engine.start(harness.workflow.getId(), 1, Json.obj(), "manual");

    assertThat(run.status()).isEqualTo(RunStatus.PAUSED_WAITING_INSTRUCTION);
    assertThat(harness.deviceRequests).isEmpty();
    assertThat(form(harness).path("missing").toString()).contains("text");
    assertThat(harness.raised.get(0).body()).contains("Text has no value");

    // Approving without the value asks again rather than sending an empty field.
    harness.engine.resume(run.id(), Json.obj().put("approved", true));
    assertThat(harness.deviceRequests).isEmpty();

    harness.engine.resume(run.id(), Json.obj().put("approved", true)
        .set("inputs", Json.obj().put("text", "typed by me")));
    assertThat(harness.deviceRequests).extracting(r -> r.input().path("text").asText())
        .containsExactly("typed by me");
  }

  @Test
  void letJizzaDecideClicksTheListedItemTheRunAskedFor() {
    EngineHarness harness = harness(CLICK_MOVIE);
    harness.toolRule = AutonomyRule.JIZZA;
    harness.deviceOutputs.put("browser.listItems", listing("Kantara", "Ramayana (Hindi)", "Dune"));
    harness.deviceOutputs.put("browser.clickItem", Json.obj().put("clicked", true));

    var run = harness.engine.start(harness.workflow.getId(), 1,
        Json.obj().put("movie", "ramayana"), "manual");

    assertThat(run.status()).isEqualTo(RunStatus.DONE);
    assertThat(harness.deviceRequests).extracting(r -> r.action())
        .containsExactly("browser.listItems", "browser.clickItem");
    assertThat(harness.deviceRequests.get(1).input().path("item").asText())
        .isEqualTo("Ramayana (Hindi)");
    assertThat(harness.raised).isEmpty();
  }

  @Test
  void letJizzaDecideAsksWithTheLiveListWhenTheItemIsNotThere() {
    EngineHarness harness = harness(CLICK_MOVIE);
    harness.toolRule = AutonomyRule.JIZZA;
    harness.deviceOutputs.put("browser.listItems", listing("Kantara", "Dune"));

    var run = harness.engine.start(harness.workflow.getId(), 1,
        Json.obj().put("movie", "Ramayana"), "manual");

    assertThat(run.status()).isEqualTo(RunStatus.PAUSED_WAITING_INSTRUCTION);
    JsonNode item = form(harness).path("fields").get(0);
    assertThat(item.path("type").asText()).isEqualTo("select");
    assertThat(item.path("options").toString()).isEqualTo("[\"Kantara\",\"Dune\"]");
    assertThat(item.path("missing").asBoolean()).isTrue();
    assertThat(harness.raised.get(0).body()).contains("does not list \"Ramayana\"");

    harness.deviceOutputs.put("browser.clickItem", Json.obj().put("clicked", true));
    harness.engine.resume(run.id(), Json.obj().put("approved", true)
        .set("inputs", Json.obj().put("item", "Dune")));

    // The list is read again after the answer, so the pick is still on the page when clicked.
    assertThat(harness.deviceRequests).extracting(r -> r.action())
        .containsExactly("browser.listItems", "browser.listItems", "browser.clickItem");
    assertThat(harness.deviceRequests.get(2).input().path("item").asText()).isEqualTo("Dune");
  }

  @Test
  void askMeOffersTheListEvenWhenTheItemMatches() {
    EngineHarness harness = harness(CLICK_MOVIE);
    harness.toolRule = AutonomyRule.ASK;
    harness.deviceOutputs.put("browser.listItems", listing("Ramayana", "Dune"));

    var run = harness.engine.start(harness.workflow.getId(), 1,
        Json.obj().put("movie", "Ramayana"), "manual");

    assertThat(run.status()).isEqualTo(RunStatus.PAUSED_WAITING_INSTRUCTION);
    JsonNode item = form(harness).path("fields").get(0);
    assertThat(item.path("value").asText()).isEqualTo("Ramayana");
    assertThat(item.path("missing").asBoolean()).isFalse();
    assertThat(item.path("options").size()).isEqualTo(2);
  }

  @Test
  void fillFormSkipsEmptyOptionalFieldsAndAsksForEmptyRequiredOnes() {
    EngineHarness harness = harness("""
        {"preset":"web","action":"browser.fillForm","version":1,"target":"sameBrowser",
         "inputs":{"fields":[
           {"element":{"strategies":[{"by":"css","value":"#n"}],"label":"Name"},"kind":"text",
            "value":"{{ input.name }}","required":true},
           {"element":{"strategies":[{"by":"css","value":"#c"}],"label":"Comments"},"kind":"text",
            "value":"{{ input.notes }}"},
           {"element":{"strategies":[{"by":"css","value":"#i"}],"label":"Interests"},
            "kind":"checkboxes","value":"{{ input.interests }}"},
           {"element":{"strategies":[{"by":"css","value":"#t"}],"label":"Terms"},
            "kind":"checkbox","value":"yes"}]}}
        """);
    harness.toolRule = AutonomyRule.ALLOW;

    var run = harness.engine.start(harness.workflow.getId(), 1,
        Json.obj().put("interests", "Music, Travel"), "manual");

    assertThat(run.status()).isEqualTo(RunStatus.PAUSED_WAITING_INSTRUCTION);
    assertThat(form(harness).path("missing").toString()).isEqualTo("[\"fields.0.value\"]");
    assertThat(form(harness).path("fields").get(0).path("label").asText()).isEqualTo("Name");

    harness.engine.resume(run.id(), Json.obj().put("approved", true)
        .set("inputs", Json.obj().put("fields.0.value", "Asha")));

    JsonNode fields = harness.deviceRequests.get(0).input().path("fields");
    assertThat(fields).hasSize(3);
    assertThat(fields.get(0).path("value").asText()).isEqualTo("Asha");
    assertThat(fields.get(1).path("value").toString()).isEqualTo("[\"Music\",\"Travel\"]");
    assertThat(fields.get(2).path("value").asBoolean()).isTrue();
  }
}
