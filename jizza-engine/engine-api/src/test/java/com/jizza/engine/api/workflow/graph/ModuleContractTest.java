package com.jizza.engine.api.workflow.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jizza.engine.api.enums.NodeType;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.util.Json;
import java.util.List;
import org.junit.jupiter.api.Test;

class ModuleContractTest {

  private static WorkflowGraph graph(String inputs, String outputs) {
    return new WorkflowGraph(List.of(
        new WorkflowNode("start", NodeType.TRIGGER, "Start", inputs == null ? null : Json.read("{\"inputs\":" + inputs + "}"), null),
        new WorkflowNode("end", NodeType.END, "Done", outputs == null ? null : Json.read("{\"outputs\":" + outputs + "}"), null)),
        List.of(new WorkflowEdge("start", "end", null)));
  }

  @Test
  void aWorkflowThatDeclaresNothingTakesAnyInput() {
    var input = Json.read("[1, 2]");
    assertThat(ModuleContract.prepareInput(graph(null, null), input)).isSameAs(input);
    assertThat(ModuleContract.inputSchema(graph(null, null))).isNull();
  }

  @Test
  void typesInputsAndFillsDefaults() {
    var g = graph("[{\"name\":\"amount\",\"type\":\"number\",\"required\":true},"
        + "{\"name\":\"vip\",\"type\":\"boolean\"},{\"name\":\"tags\",\"type\":\"list\"},"
        + "{\"name\":\"city\",\"type\":\"text\",\"default\":\"Chennai\"}]", null);
    var out = ModuleContract.prepareInput(g, Json.read("{\"amount\":\"1,250.5\",\"vip\":\"yes\",\"tags\":\"[\\\"a\\\"]\",\"extra\":1}"));
    assertThat(out.path("amount").asDouble()).isEqualTo(1250.5);
    assertThat(out.path("vip").asBoolean()).isTrue();
    assertThat(out.path("tags").get(0).asText()).isEqualTo("a");
    assertThat(out.path("city").asText()).isEqualTo("Chennai");
    assertThat(out.path("extra").asInt()).as("undeclared fields pass through").isEqualTo(1);
  }

  @Test
  void namesEveryMissingRequiredInput() {
    var g = graph("[{\"name\":\"email\",\"required\":true},{\"name\":\"amount\",\"type\":\"number\",\"required\":true}]", null);
    assertThatThrownBy(() -> ModuleContract.prepareInput(g, Json.read("{\"email\":\"  \"}")))
        .isInstanceOf(ValidationException.class).hasMessageContaining("the inputs email, amount");
  }

  @Test
  void describesItsInputsAsAJsonSchema() {
    var schema = ModuleContract.inputSchema(graph(
        "[{\"name\":\"amount\",\"type\":\"number\",\"required\":true,\"description\":\"In rupees\"}]", null));
    assertThat(schema.path("properties").path("amount").path("type").asText()).isEqualTo("number");
    assertThat(schema.path("properties").path("amount").path("description").asText()).isEqualTo("In rupees");
    assertThat(schema.path("required").get(0).asText()).isEqualTo("amount");
  }

  @Test
  void aDateAndTimeInputKeepsTheSitesFormat() {
    var g = graph("[{\"name\":\"departs\",\"type\":\"datetime\",\"format\":\"DD/MM/YYYY HH:mm\"},"
        + "{\"name\":\"journey\",\"type\":\"date\",\"format\":\"DD/MM/YYYY\"}]", null);
    var inputs = ModuleContract.inputsOf(g);
    assertThat(inputs.get(0).type()).isEqualTo("datetime");
    assertThat(inputs.get(0).format()).isEqualTo("DD/MM/YYYY HH:mm");
    var schema = ModuleContract.inputSchema(g).path("properties");
    assertThat(schema.path("departs").path("format").asText()).isEqualTo("date-time");
    assertThat(schema.path("departs").path("x-type").asText()).isEqualTo("datetime");
    assertThat(schema.path("departs").path("x-date-format").asText()).isEqualTo("DD/MM/YYYY HH:mm");
    assertThat(schema.path("journey").has("x-type")).isFalse();
    assertThat(ModuleContract.prepareInput(g, Json.read("{\"departs\":\"2026-11-20T18:30\"}"))
        .path("departs").asText()).isEqualTo("2026-11-20T18:30");
  }

  @Test
  void refusesBadNamesRepeatsAndOutputsWithoutAValue() {
    assertThatThrownBy(() -> ModuleContract.validate(graph("[{\"name\":\"first name\"}]", null)))
        .hasMessageContaining("letters, digits and _");
    assertThatThrownBy(() -> ModuleContract.validate(graph("[{\"name\":\"a\"},{\"name\":\"a\"}]", null)))
        .hasMessageContaining("two inputs named 'a'");
    assertThatThrownBy(() -> ModuleContract.validate(graph(null, "[{\"name\":\"total\",\"value\":\"\"}]")))
        .hasMessageContaining("'total' needs a value");
    ModuleContract.validate(graph("[{\"name\":\"a\"}]", "[{\"name\":\"total\",\"value\":\"{{ input.a }}\"}]"));
    assertThat(ModuleContract.outputsOf(graph(null, "[{\"name\":\"total\",\"value\":\"x\"}]")).get(0).name()).isEqualTo("total");
  }
}
