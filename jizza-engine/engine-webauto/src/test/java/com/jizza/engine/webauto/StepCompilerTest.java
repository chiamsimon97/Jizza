package com.jizza.engine.webauto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.util.Json;
import java.util.List;
import org.junit.jupiter.api.Test;

class StepCompilerTest {

  private static JsonNode step(String json) {
    return Json.read(json);
  }

  private static final JsonNode OPEN = step("""
      {"seq":0,"action":"browser.open","version":2,"label":"Open portal",
       "input":{"url":"https://portal.example.com"},"meta":{}}""");
  private static final JsonNode SET = step("""
      {"seq":1,"action":"browser.setText","version":1,"label":"Set invoice number",
       "input":{"element":{"strategies":[{"by":"css","value":"#inv"}]},"text":"INV-2041"},
       "meta":{},
       "mappings":{"text":{"name":"invoiceNo","type":"text","default":"INV-2041"}}}""");
  private static final JsonNode READ = step("""
      {"seq":2,"action":"browser.getText","version":1,"label":"Invoice total",
       "input":{"element":{"strategies":[{"by":"css","value":"#total"}]}},"meta":{}}""");
  private static final JsonNode TABLE = step("""
      {"seq":3,"action":"browser.extractTable","version":1,"label":"Line items",
       "input":{"element":{"strategies":[{"by":"css","value":"table"}]}},"meta":{}}""");

  @Test
  void compilesALinearGraphWithMappedInputsAndOutputs() {
    var graph = StepCompiler.compile("https://portal.example.com", List.of(OPEN, SET, READ, TABLE));

    var nodes = graph.path("nodes");
    assertThat(nodes).hasSize(7);
    assertThat(nodes.get(0).path("type").asText()).isEqualTo("TRIGGER");
    assertThat(nodes.get(5).path("config").path("action").asText()).isEqualTo("browser.close");
    assertThat(nodes.get(5).path("label").asText()).isEqualTo("Close browser");
    assertThat(nodes.get(6).path("type").asText()).isEqualTo("END");
    var set = nodes.get(2).path("config");
    assertThat(set.path("preset").asText()).isEqualTo("web");
    assertThat(set.path("target").asText()).isEqualTo("sameBrowser");
    assertThat(set.path("inputs").path("text").asText()).isEqualTo("{{ input.invoiceNo }}");

    var inputs = nodes.get(0).path("config").path("inputs");
    assertThat(inputs).hasSize(1);
    assertThat(inputs.get(0).path("name").asText()).isEqualTo("invoiceNo");
    assertThat(inputs.get(0).path("default").asText()).isEqualTo("INV-2041");
    assertThat(inputs.get(0).path("required").asBoolean()).isFalse();

    var outputs = nodes.get(6).path("config").path("outputs");
    assertThat(outputs).hasSize(2);
    assertThat(outputs.get(0).path("name").asText()).isEqualTo("invoiceTotal");
    assertThat(outputs.get(0).path("value").asText()).isEqualTo("{{ nodes.step_3.text }}");
    assertThat(outputs.get(1).path("type").asText()).isEqualTo("list");
    assertThat(outputs.get(1).path("value").asText()).isEqualTo("{{ nodes.step_4.rows }}");

    var edges = graph.path("edges");
    assertThat(edges).hasSize(6);
    assertThat(edges.get(0).path("from").asText()).isEqualTo("start");
    assertThat(edges.get(4).path("to").asText()).isEqualTo("close");
    assertThat(edges.get(5).path("to").asText()).isEqualTo("end");
  }

  @Test
  void aListChoiceAndItsCardBecomeInputs() {
    JsonNode choose = step("""
        {"seq":1,"action":"browser.clickItem","version":1,"label":"Choose the time \\"01:15 PM\\"",
         "input":{"element":{"strategies":[{"by":"css","value":".time"}]},"item":"01:15 PM",
                  "within":{"element":{"strategies":[{"by":"css","value":".venue"}]},"item":"Vijay Park"}},
         "meta":{"list":{"kind":"time","inputName":"time"}},
         "mappings":{"item":{"name":"time","type":"text","default":"01:15 PM"},
                     "within.item":{"name":"cinema","type":"text","default":"Vijay Park"}}}""");
    var graph = StepCompiler.compile("https://tickets.example.com", List.of(OPEN, choose));

    var config = graph.path("nodes").get(2).path("config");
    assertThat(config.path("action").asText()).isEqualTo("browser.clickItem");
    assertThat(config.path("inputs").path("item").asText()).isEqualTo("{{ input.time }}");
    assertThat(config.path("inputs").path("within").path("item").asText())
        .isEqualTo("{{ input.cinema }}");
    assertThat(config.path("inputs").path("within").path("element").path("strategies")).hasSize(1);
    var inputs = graph.path("nodes").get(0).path("config").path("inputs");
    assertThat(inputs).extracting(n -> n.path("name").asText()).containsExactly("time", "cinema");
    assertThat(inputs.get(1).path("default").asText()).isEqualTo("Vijay Park");

    var inline = StepCompiler.compile("https://tickets.example.com", List.of(OPEN, choose), true);
    assertThat(inline.path("nodes").get(1).path("config").path("inputs").path("within")
        .path("item").asText()).isEqualTo("Vijay Park");
  }

  @Test
  void aRecordingThatAlreadyEndsByClosingGetsNoSecondClose() {
    JsonNode close = step("""
        {"seq":2,"action":"browser.close","version":1,"label":"Close browser",
         "input":{"what":"session"},"meta":{}}""");
    var graph = StepCompiler.compile("https://x.test", List.of(OPEN, close));
    assertThat(graph.path("nodes").findValuesAsText("action").stream()
        .filter("browser.close"::equals)).hasSize(1);
  }

  @Test
  void prependsOpenUrlWhenTheFirstStepIsNotAnOpen() {
    var graph = StepCompiler.compile("https://portal.example.com", List.of(READ));

    var first = graph.path("nodes").get(1);
    assertThat(first.path("config").path("action").asText()).isEqualTo("browser.open");
    assertThat(first.path("config").path("inputs").path("url").asText())
        .isEqualTo("https://portal.example.com");
  }

  @Test
  void aSensitiveStepNeedsASavedLoginAndNeverKeepsAValue() {
    JsonNode password = step("""
        {"seq":1,"action":"browser.setText","version":1,"label":"Password",
         "input":{"element":{"strategies":[{"by":"css","value":"#pw"}]},"text":"hunter2"},
         "meta":{"sensitive":true},"secretRef":null}""");

    assertThatThrownBy(() -> StepCompiler.compile("https://x.test", List.of(OPEN, password)))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("Choose a saved login for the password step");

    JsonNode withLogin = step(password.toString().replace("\"secretRef\":null", "\"secretRef\":\"login-1\""));
    var graph = StepCompiler.compile("https://x.test", List.of(OPEN, withLogin));
    var inputs = graph.path("nodes").get(2).path("config").path("inputs");
    assertThat(inputs.path("secretRef").asText()).isEqualTo("login-1");
    assertThat(inputs.has("text")).isFalse();
    assertThat(graph.toString()).doesNotContain("hunter2");
  }

  @Test
  void refusesAnInvalidInputName() {
    JsonNode bad = step(SET.toString().replace("invoiceNo", "1 bad"));
    assertThatThrownBy(() -> StepCompiler.compile("https://x.test", List.of(OPEN, bad)))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void outputNamesStayUnique() {
    var graph = StepCompiler.compile("https://x.test", List.of(OPEN, READ, READ));
    var outputs = graph.path("nodes").get(5).path("config").path("outputs");
    assertThat(outputs.get(0).path("name").asText()).isNotEqualTo(outputs.get(1).path("name").asText());
  }

  @Test
  void inlineStepsHaveNoStartOrEndAndKeepRecordedValues() {
    var graph = StepCompiler.compile("https://portal.example.com", List.of(OPEN, SET, READ), true);

    var nodes = graph.path("nodes");
    assertThat(nodes).hasSize(3);
    assertThat(nodes.findValuesAsText("type")).containsOnly("DEVICE_ACTION");
    assertThat(nodes.get(1).path("config").path("inputs").path("text").asText()).isEqualTo("INV-2041");
    assertThat(graph.path("edges")).hasSize(2);
    assertThat(graph.path("edges").get(0).path("from").asText()).isEqualTo("step_1");
  }

  @Test
  void aDesktopRecordingTargetsItsPcWithNoBrowserSteps() {
    var pc = java.util.UUID.randomUUID();
    JsonNode open = step("""
        {"seq":0,"action":"ui.openApp","version":1,"label":"Open Notepad","input":{"app":"notepad"},"meta":{}}""");
    JsonNode type = step("""
        {"seq":1,"action":"ui.type","version":1,"label":"Type into Text editor",
         "input":{"app":"notepad","element":"Document:Text editor","text":"Hello"},"meta":{},
         "mappings":{"text":{"name":"note","type":"text","default":"Hello"}}}""");
    JsonNode read = step("""
        {"seq":2,"action":"ui.readWindow","version":1,"label":"Read Notepad","input":{"app":"notepad"},"meta":{}}""");

    var graph = StepCompiler.compileDesktop(pc, List.of(open, type, read), false);

    var nodes = graph.path("nodes");
    assertThat(nodes.findValuesAsText("label"))
        .containsExactly("Start", "Open Notepad", "Type into Text editor", "Read Notepad", "End");
    var typeConfig = nodes.get(2).path("config");
    assertThat(typeConfig.path("target").asText()).isEqualTo(pc.toString());
    assertThat(typeConfig.has("preset")).isFalse();
    assertThat(typeConfig.path("inputs").path("text").asText()).isEqualTo("{{ input.note }}");
    assertThat(nodes.get(4).path("config").path("outputs").get(0).path("value").asText())
        .isEqualTo("{{ nodes.step_3.text }}");
  }

  @Test
  void aDesktopPasswordNeedsASecretSavedOnThePc() {
    JsonNode password = step("""
        {"seq":0,"action":"ui.type","version":1,"label":"Type password",
         "input":{"app":"sap","element":"Edit:Password"},"meta":{"sensitive":true}}""");
    var pc = java.util.UUID.randomUUID();
    assertThatThrownBy(() -> StepCompiler.compileDesktop(pc, List.of(password), false))
        .isInstanceOf(ValidationException.class).hasMessageContaining("Secrets");
  }
}
