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

/** A section the page repeats, one row per passenger: its entries are one list. */
class FillRowsStepTest {

  private static EngineHarness harness() {
    return harness(false);
  }

  private static EngineHarness harness(boolean optional) {
    EngineHarness harness = new EngineHarness(new WorkflowGraph(List.of(
        new WorkflowNode("start", NodeType.TRIGGER, "start", null, null),
        new WorkflowNode("step", NodeType.DEVICE_ACTION, "step", Json.read("""
            {"preset":"web","action":"browser.fillRows","version":1,"target":"sameBrowser",
             "inputs":{"row":{"strategies":[{"by":"css","value":"app-passenger"}]},
                       "rowCss":"app-passenger",
                       "columns":[{"key":"name","label":"Name","css":"input","kind":"text"},
                                  {"key":"age","label":"Age","css":"input.age","kind":"text"}],
                       "rows":"{{ input.passengers }}"%s}}
            """.formatted(optional ? ",\"optional\":true" : "")), new WorkflowNode.RetryPolicy(1, 1, 1, 10)),
        new WorkflowNode("finish", NodeType.END, "finish", null, null)),
        List.of(new WorkflowEdge("start", "step", null), new WorkflowEdge("step", "finish", null))));
    harness.deviceInputSchemas.put("browser.fillRows", Json.read("{\"type\":\"object\","
        + "\"properties\":{\"row\":{\"$ref\":\"device-actions.json#/schemas/locatorBundle\"},"
        + "\"rowCss\":{\"type\":\"string\"},\"columns\":{\"type\":\"array\"},"
        + "\"rows\":{\"type\":\"array\"}},\"required\":[\"row\",\"rowCss\",\"columns\",\"rows\"]}"));
    harness.deviceOutputs.put("browser.fillRows", Json.obj().put("rows", 2));
    harness.toolRule = AutonomyRule.ALLOW;
    return harness;
  }

  private static JsonNode rowsField(EngineHarness harness) {
    JsonNode form = harness.raised.get(harness.raised.size() - 1).context().path("form");
    for (JsonNode f : form.path("fields")) if ("rows".equals(f.path("key").asText())) return f;
    return null;
  }

  @Test
  void theEntriesGivenToTheRunFillTheRows() {
    EngineHarness harness = harness();
    var passengers = Json.mapper().createArrayNode();
    passengers.addObject().put("name", "Asha").put("age", 34);
    passengers.addObject().put("name", "Ravi").put("age", 36);

    var run = harness.engine.start(harness.workflow.getId(), 1,
        Json.obj().set("passengers", passengers), "manual");

    assertThat(run.status()).isEqualTo(RunStatus.DONE);
    assertThat(harness.deviceRequests.get(0).input().path("rows").toString())
        .isEqualTo("[{\"name\":\"Asha\",\"age\":34},{\"name\":\"Ravi\",\"age\":36}]");
  }

  @Test
  void noEntriesAsksForThemAsATableOfTheColumns() {
    EngineHarness harness = harness();

    var run = harness.engine.start(harness.workflow.getId(), 1, Json.obj(), "manual");

    assertThat(run.status()).isEqualTo(RunStatus.PAUSED_WAITING_INSTRUCTION);
    JsonNode rows = rowsField(harness);
    assertThat(rows.path("type").asText()).isEqualTo("rows");
    assertThat(rows.path("label").asText()).isEqualTo("Passengers");
    assertThat(rows.path("missing").asBoolean()).isTrue();
    assertThat(rows.path("columns").toString())
        .isEqualTo("[{\"key\":\"name\",\"label\":\"Name\"},{\"key\":\"age\",\"label\":\"Age\"}]");

    // The card's table answers as JSON text; empty entries are dropped.
    harness.engine.resume(run.id(), Json.obj().put("approved", true).set("inputs",
        Json.obj().put("rows", "[{\"name\":\"Asha\",\"age\":\"34\"},{\"name\":\"\",\"age\":\"\"}]")));
    assertThat(harness.deviceRequests).hasSize(1);
    assertThat(harness.deviceRequests.get(0).input().path("rows").toString())
        .isEqualTo("[{\"name\":\"Asha\",\"age\":\"34\"}]");
  }

  @Test
  void aKindOfRowThatMayBeNoneRunsWithoutAskingWhenThereAreNone() {
    EngineHarness harness = harness(true);

    var run = harness.engine.start(harness.workflow.getId(), 1,
        Json.obj().set("passengers", Json.mapper().createArrayNode()), "manual");

    assertThat(run.status()).isEqualTo(RunStatus.DONE);
    assertThat(harness.deviceRequests).hasSize(1);
    assertThat(harness.deviceRequests.get(0).input().path("rows").toString()).isEqualTo("[]");
  }
}
