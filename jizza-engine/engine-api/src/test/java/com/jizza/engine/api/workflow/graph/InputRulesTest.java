package com.jizza.engine.api.workflow.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jizza.engine.api.enums.NodeType;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.util.Json;
import java.util.List;
import org.junit.jupiter.api.Test;

class InputRulesTest {

  private static WorkflowGraph graph(String inputs) {
    return new WorkflowGraph(List.of(
        new WorkflowNode("start", NodeType.TRIGGER, "Start", Json.read("{\"inputs\":" + inputs + "}"), null),
        new WorkflowNode("end", NodeType.END, "Done", null, null)),
        List.of(new WorkflowEdge("start", "end", null)));
  }

  private static final String CLASSES = "[{\"name\":\"travelClass\",\"type\":\"text\","
      + "\"options\":[\"AC 3 Tier (3A)\",\"AC 2 Tier (2A)\",\"Sleeper (SL)\"],"
      + "\"synonyms\":{\"Third AC\":\"AC 3 Tier (3A)\",\"3A\":\"AC 3 Tier (3A)\"}}]";

  @Test
  void anExactOptionPassesUntouched() {
    var out = ModuleContract.prepareInput(graph(CLASSES), Json.read("{\"travelClass\":\"Sleeper (SL)\"}"));
    assertThat(out.path("travelClass").asText()).isEqualTo("Sleeper (SL)");
  }

  @Test
  void caseAndSpacingAreForgiven() {
    var out = ModuleContract.prepareInput(graph(CLASSES), Json.read("{\"travelClass\":\"ac 3 tier (3a)\"}"));
    assertThat(out.path("travelClass").asText()).isEqualTo("AC 3 Tier (3A)");
  }

  @Test
  void aSynonymLandsOnTheOptionTheSiteShows() {
    assertThat(ModuleContract.prepareInput(graph(CLASSES), Json.read("{\"travelClass\":\"third ac\"}"))
        .path("travelClass").asText()).isEqualTo("AC 3 Tier (3A)");
    assertThat(ModuleContract.prepareInput(graph(CLASSES), Json.read("{\"travelClass\":\"3A\"}"))
        .path("travelClass").asText()).isEqualTo("AC 3 Tier (3A)");
  }

  @Test
  void aUniquePartialMatchIsAccepted() {
    assertThat(ModuleContract.prepareInput(graph(CLASSES), Json.read("{\"travelClass\":\"sleeper\"}"))
        .path("travelClass").asText()).isEqualTo("Sleeper (SL)");
  }

  @Test
  void anAmbiguousOrUnknownValueIsRefusedNamingTheChoices() {
    assertThatThrownBy(() -> ModuleContract.prepareInput(graph(CLASSES), Json.read("{\"travelClass\":\"AC\"}")))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("not one of the choices for 'travelClass'")
        .hasMessageContaining("AC 3 Tier (3A)");
    assertThatThrownBy(() -> ModuleContract.prepareInput(graph(CLASSES), Json.read("{\"travelClass\":\"First AC\"}")))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void numberBoundsAreChecked() {
    var g = graph("[{\"name\":\"seats\",\"type\":\"number\",\"min\":1,\"max\":6}]");
    assertThat(ModuleContract.prepareInput(g, Json.read("{\"seats\":4}")).path("seats").asInt()).isEqualTo(4);
    assertThatThrownBy(() -> ModuleContract.prepareInput(g, Json.read("{\"seats\":0}")))
        .hasMessageContaining("at least 1");
    assertThatThrownBy(() -> ModuleContract.prepareInput(g, Json.read("{\"seats\":\"7\"}")))
        .hasMessageContaining("at most 6");
  }

  @Test
  void textPatternAndLengthAreChecked() {
    var g = graph("[{\"name\":\"pnr\",\"type\":\"text\",\"pattern\":\"[0-9]{10}\","
        + "\"patternHint\":\"10 digits\",\"maxLength\":10}]");
    assertThat(ModuleContract.prepareInput(g, Json.read("{\"pnr\":\"1234567890\"}"))
        .path("pnr").asText()).isEqualTo("1234567890");
    assertThatThrownBy(() -> ModuleContract.prepareInput(g, Json.read("{\"pnr\":\"12345678901\"}")))
        .hasMessageContaining("at most 10 characters");
    assertThatThrownBy(() -> ModuleContract.prepareInput(g, Json.read("{\"pnr\":\"abc\"}")))
        .hasMessageContaining("10 digits");
  }

  @Test
  void aBrokenPatternNeverBlocksARun() {
    var g = graph("[{\"name\":\"pnr\",\"type\":\"text\",\"pattern\":\"[unclosed\"}]");
    assertThat(ModuleContract.prepareInput(g, Json.read("{\"pnr\":\"anything\"}"))
        .path("pnr").asText()).isEqualTo("anything");
  }

  private static final String PASSENGERS = "[{\"name\":\"passengers\",\"type\":\"list\","
      + "\"minRows\":1,\"maxRows\":4,\"columns\":["
      + "{\"key\":\"name\",\"label\":\"Name\",\"required\":true},"
      + "{\"key\":\"age\",\"label\":\"Age\",\"min\":1,\"max\":120},"
      + "{\"key\":\"berth\",\"label\":\"Berth\",\"options\":[\"Lower\",\"Middle\",\"Upper\"]}]}]";

  @Test
  void tooManyOrTooFewRowsAreRefusedBeforeAnythingTouchesAPage() {
    assertThatThrownBy(() -> ModuleContract.prepareInput(graph(PASSENGERS), Json.read("{\"passengers\":[]}")))
        .hasMessageContaining("at least 1 entry");
    assertThatThrownBy(() -> ModuleContract.prepareInput(graph(PASSENGERS), Json.read(
        "{\"passengers\":[{\"name\":\"a\"},{\"name\":\"b\"},{\"name\":\"c\"},{\"name\":\"d\"},{\"name\":\"e\"},{\"name\":\"f\"}]}")))
        .hasMessageContaining("at most 4 entries, not 6");
  }

  @Test
  void eachRowIsCheckedByItsColumns() {
    assertThatThrownBy(() -> ModuleContract.prepareInput(graph(PASSENGERS), Json.read(
        "{\"passengers\":[{\"name\":\"Asha\"},{\"age\":30}]}")))
        .hasMessageContaining("Entry 2 of 'passengers' needs 'Name'");
    assertThatThrownBy(() -> ModuleContract.prepareInput(graph(PASSENGERS), Json.read(
        "{\"passengers\":[{\"name\":\"Asha\",\"age\":\"150\"}]}")))
        .hasMessageContaining("at most 120");
    var out = ModuleContract.prepareInput(graph(PASSENGERS), Json.read(
        "{\"passengers\":[{\"name\":\"Asha\",\"age\":30,\"berth\":\"lower\"}]}"));
    assertThat(out.path("passengers").get(0).path("berth").asText())
        .as("a column with options is normalised too").isEqualTo("Lower");
  }

  @Test
  void anInputAskedAtItsStepIsNotNeededToStartTheRun() {
    var g = graph("[{\"name\":\"otp\",\"type\":\"text\",\"required\":true,\"ask\":\"atStep\"},"
        + "{\"name\":\"from\",\"type\":\"text\",\"required\":true}]");
    assertThatThrownBy(() -> ModuleContract.prepareInput(g, Json.read("{}")))
        .hasMessageContaining("from").extracting(Throwable::getMessage).asString().doesNotContain("otp");
    assertThat(ModuleContract.prepareInput(g, Json.read("{\"from\":\"Chennai\"}"))
        .path("from").asText()).isEqualTo("Chennai");
  }

  @Test
  void anInputWithoutRulesBehavesExactlyAsBefore() {
    var g = graph("[{\"name\":\"city\",\"type\":\"text\"}]");
    assertThat(ModuleContract.prepareInput(g, Json.read("{\"city\":\"anything at all\"}"))
        .path("city").asText()).isEqualTo("anything at all");
  }

  @Test
  void synonymsThatPointAtEachOtherDoNotLoop() {
    var g = graph("[{\"name\":\"c\",\"type\":\"text\",\"options\":[\"One\"],"
        + "\"synonyms\":{\"x\":\"y\",\"y\":\"x\"}}]");
    assertThatThrownBy(() -> ModuleContract.prepareInput(g, Json.read("{\"c\":\"x\"}")))
        .isInstanceOf(ValidationException.class);
  }
}
