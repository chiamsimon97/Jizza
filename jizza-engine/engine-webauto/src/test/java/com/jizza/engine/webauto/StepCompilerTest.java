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
  void mandatoryInputsAreMarkedForTheCard() {
    JsonNode name = step("""
        {"seq":1,"action":"browser.setText","version":1,"label":"Passenger name",
         "input":{"element":{"strategies":[{"by":"css","value":"#n"}]},"text":"Chiam"},"meta":{},
         "mappings":{"text":{"name":"passenger","type":"text","default":"","required":true}}}""");
    JsonNode form = step("""
        {"seq":2,"action":"browser.fillForm","version":1,"label":"Passenger",
         "input":{"fields":[
           {"element":{"strategies":[{"by":"css","value":"#age"}]},"kind":"text","value":"30"},
           {"element":{"strategies":[{"by":"css","value":"#g"}]},"kind":"select","value":"M"}]},
         "meta":{},
         "mappings":{"fields.1.value":{"name":"gender","type":"text","default":"","required":true},
                     "fields.0.value":{"name":"age","type":"text","default":"30"}}}""");

    var nodes = StepCompiler.compile("https://x.example.com", List.of(OPEN, name, form))
        .path("nodes");

    assertThat(nodes.get(2).path("config").path("requiredInputs").toString())
        .isEqualTo("[\"text\"]");
    var fields = nodes.get(3).path("config").path("inputs").path("fields");
    assertThat(fields.get(1).path("required").asBoolean()).isTrue();
    assertThat(fields.get(0).path("required").asBoolean()).isFalse();
    assertThat(nodes.get(3).path("config").has("requiredInputs")).isFalse();
  }

  @Test
  void aDeviceLayerGoesOnAlongTheButtonClickedWhileRecording() {
    JsonNode layer = step("""
        {"seq":1,"action":"browser.fillLayer","version":1,"label":"Create task",
         "input":{"groups":[{"id":"g1","title":"Details"}],
           "fields":[{"id":"f1","label":"Project","kind":"select","section":"config","group":"g1",
             "element":{"strategies":[{"by":"css","value":"#p"}]},"value":"Jizza"}],
           "buttons":[{"id":"b1","name":"Submit","element":{"strategies":[{"by":"text","value":"Submit"}]}},
                      {"id":"b2","name":"Cancel","element":{"strategies":[{"by":"text","value":"Cancel"}]}}],
           "button":"Cancel"},
         "meta":{"url":"https://x.example.com/tasks/new"},
         "mappings":{"fields.0.value":{"name":"project","type":"text","default":"Jizza","required":true}}}""");

    var graph = StepCompiler.compile("https://x.example.com", List.of(OPEN, layer));

    var config = graph.path("nodes").get(2).path("config");
    assertThat(config.path("inputs").path("fields").get(0).path("value").asText())
        .isEqualTo("{{ input.project }}");
    assertThat(config.path("inputs").path("fields").get(0).path("required").asBoolean()).isTrue();
    assertThat(config.path("inputs").path("expectOrigin").asText()).isEqualTo("https://x.example.com");
    JsonNode out = null;
    for (JsonNode e : graph.path("edges")) {
      if (e.path("from").asText().equals(graph.path("nodes").get(2).path("id").asText())) out = e;
    }
    assertThat(out.path("branch").asText()).isEqualTo("b2");
  }

  @Test
  void theRowsOfARepeatedSectionBecomeOneListInputWithItsColumns() {
    JsonNode rows = step("""
        {"seq":1,"action":"browser.fillRows","version":1,"label":"Fill a row for each Passenger",
         "input":{"row":{"strategies":[{"by":"css","value":"app-passenger"}]},
                  "rowCss":"app-passenger","add":{"strategies":[{"by":"css","value":"#add"}]},
                  "columns":[{"key":"name","label":"Name","css":"input","kind":"text"},
                             {"key":"age","label":"Age","css":"input.age","kind":"text"}],
                  "rows":[{"name":"Asha","age":"34"},{"name":"Ravi","age":"36"}]},
         "meta":{},
         "mappings":{"rows":{"name":"passengers","type":"list",
                             "default":[{"name":"Asha","age":"34"},{"name":"Ravi","age":"36"}]}}}""");

    var nodes = StepCompiler.compile("https://x.example.com", List.of(OPEN, rows)).path("nodes");

    assertThat(nodes.get(2).path("config").path("inputs").path("rows").asText())
        .isEqualTo("{{ input.passengers }}");
    var input = nodes.get(0).path("config").path("inputs").get(0);
    assertThat(input.path("name").asText()).isEqualTo("passengers");
    assertThat(input.path("type").asText()).isEqualTo("list");
    assertThat(input.path("default").size()).isEqualTo(2);
    assertThat(input.path("columns").toString())
        .isEqualTo("[{\"key\":\"name\",\"label\":\"Name\"},{\"key\":\"age\",\"label\":\"Age\"}]");
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
    // The browser agent takes the login and which of its fields to type.
    assertThat(inputs.path("secretRef").path("login").asText()).isEqualTo("login-1");
    assertThat(inputs.path("secretRef").path("field").asText()).isEqualTo("password");
    assertThat(inputs.has("text")).isFalse();
    assertThat(graph.toString()).doesNotContain("hunter2");
  }

  @Test
  void aUsernameBoxCanTypeTheSavedLoginsUsername() {
    JsonNode user = step("""
        {"seq":1,"action":"browser.setText","version":1,"label":"User Name",
         "input":{"element":{"strategies":[{"by":"css","value":"#user"}]},"text":"chiam"},
         "meta":{"sensitive":false},"secretRef":"login-1"}""");

    var graph = StepCompiler.compile("https://x.test", List.of(OPEN, user));
    var inputs = graph.path("nodes").get(2).path("config").path("inputs");
    assertThat(inputs.path("secretRef").path("login").asText()).isEqualTo("login-1");
    assertThat(inputs.path("secretRef").path("field").asText()).isEqualTo("username");
    assertThat(inputs.has("text")).isFalse();
  }

  @Test
  void aSavedLoginReadsBackFromTheWorkflowInEitherShape() {
    assertThat(StepCompiler.secretId(Json.obj().put("login", "login-1").put("field", "password")))
        .isEqualTo("login-1");
    assertThat(StepCompiler.secretId(Json.mapper().getNodeFactory().textNode("login-2")))
        .isEqualTo("login-2");
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

  private static JsonNode click(int seq, String css) {
    return step("{\"seq\":" + seq + ",\"action\":\"browser.click\",\"version\":2,\"label\":\"Click "
        + css + "\",\"input\":{\"element\":{\"strategies\":[{\"by\":\"css\",\"value\":\"" + css
        + "\"}]}},\"meta\":{}}");
  }

  private static final JsonNode IF_POPUP = step("""
      {"seq":10,"action":"flow.if","version":1,"label":"Only if \\"Select format\\" is shown",
       "input":{"element":{"strategies":[{"by":"css","value":"#popup"}]},"timeoutMs":2000,"when":"shown"},
       "meta":{}}""");
  private static final JsonNode ELSE = step("""
      {"seq":11,"action":"flow.else","version":1,"label":"Otherwise","input":{},"meta":{}}""");
  private static final JsonNode END_IF = step("""
      {"seq":12,"action":"flow.endIf","version":1,"label":"End","input":{},"meta":{}}""");

  @Test
  void aConditionWithOtherwiseRunsOneOfItsTwoPathsThenTheStepsAfter() {
    var graph = StepCompiler.compile("https://portal.example.com", List.of(OPEN, IF_POPUP,
        click(3, "#tamil-3d"), ELSE, click(4, "#skip"), END_IF, click(5, "#date")));

    String branch = null;
    for (JsonNode n : graph.path("nodes")) {
      if ("BRANCH".equals(n.path("type").asText())) branch = n.path("id").asText();
    }
    assertThat(graph.path("nodes").toString()).contains("\"else\":true");
    List<String> edges = new java.util.ArrayList<>();
    graph.path("edges").forEach(e -> edges.add(e.path("from").asText() + ">" + e.path("to").asText()
        + (e.has("branch") ? ":" + e.path("branch").asText() : "")));
    assertThat(edges).contains(branch + ">step_3:true", branch + ">step_5:false",
        "step_3>step_7", "step_5>step_7");

    // Opened again for editing, it reads back as the same steps.
    var steps = RecordingGraph.open(graph).steps();
    assertThat(steps.stream().map(st -> st.path("action").asText()).toList()).containsExactly(
        "browser.open", "flow.if", "browser.click", "flow.else", "browser.click", "flow.endIf",
        "browser.click", "browser.close");
  }

  @Test
  void anEmptyOtherwiseOrOneOutsideAConditionIsLeftOut() {
    var graph = StepCompiler.compile("https://portal.example.com", List.of(OPEN, ELSE, IF_POPUP,
        click(3, "#tamil-3d"), ELSE, END_IF, click(5, "#date")));
    assertThat(graph.path("nodes").toString()).doesNotContain("\"else\":true");
    var steps = RecordingGraph.open(graph).steps();
    assertThat(steps.stream().map(st -> st.path("action").asText()).toList()).containsExactly(
        "browser.open", "flow.if", "browser.click", "flow.endIf", "browser.click", "browser.close");
  }

  @Test
  void anInputIsGroupedByItsPageAndCarriesThePersonsRules() {
    JsonNode set = step("""
        {"seq":1,"action":"browser.setText","version":1,"label":"Seats",
         "input":{"element":{"strategies":[{"by":"css","value":"#n"}]},"text":"2"},
         "meta":{"title":"Passenger details","url":"https://www.shop.example.com/book"},
         "mappings":{"text":{"name":"seats","type":"number","default":"2","label":"Seats",
                             "min":1,"max":6}}}""");
    JsonNode otp = step("""
        {"seq":2,"action":"browser.setText","version":1,"label":"One-time code",
         "input":{"element":{"strategies":[{"by":"css","value":"#otp"}]},"text":"1234"},
         "meta":{"url":"https://www.shop.example.com/verify"},
         "mappings":{"text":{"name":"code","type":"text","default":"","required":true}}}""");

    var inputs = StepCompiler.compile("https://shop.example.com", List.of(OPEN, set, otp))
        .path("nodes").get(0).path("config").path("inputs");

    assertThat(inputs.get(0).path("group").asText()).isEqualTo("Passenger details");
    assertThat(inputs.get(0).path("label").asText()).isEqualTo("Seats");
    assertThat(inputs.get(0).path("min").asInt()).isEqualTo(1);
    assertThat(inputs.get(0).path("max").asInt()).isEqualTo(6);
    assertThat(inputs.get(1).path("group").asText()).as("no title: the site's host").isEqualTo("shop.example.com");
    assertThat(inputs.get(1).path("ask").asText()).as("mandatory: asked at its step").isEqualTo("atStep");
  }

  @Test
  void anInputAskedAtItsStepMakesTheCardWaitForIt() {
    JsonNode set = step("""
        {"seq":1,"action":"browser.setText","version":1,"label":"Coupon",
         "input":{"element":{"strategies":[{"by":"css","value":"#c"}]},"text":"SAVE10"},
         "meta":{},
         "mappings":{"text":{"name":"coupon","type":"text","default":"SAVE10","ask":"atStep"}}}""");
    var nodes = StepCompiler.compile("https://x.example.com", List.of(OPEN, set)).path("nodes");
    assertThat(nodes.get(2).path("config").path("requiredInputs").toString()).isEqualTo("[\"text\"]");
  }

  @Test
  void aStaticDropdownAcceptsOnlyTheItemsItListed() {
    JsonNode choose = step("""
        {"seq":1,"action":"browser.selectOption","version":1,"label":"Class",
         "input":{"element":{"strategies":[{"by":"css","value":"select"}]},"items":["Gold"],
                  "mode":"static","options":[{"name":"Silver"},{"name":"Gold"},{"name":"Platinum"}]},
         "meta":{},
         "mappings":{"items":{"name":"tier","type":"text","default":"Gold"}}}""");
    JsonNode dynamic = step("""
        {"seq":2,"action":"browser.selectOption","version":1,"label":"Slot",
         "input":{"element":{"strategies":[{"by":"css","value":"#slot"}]},"items":["9am"],"mode":"dynamic"},
         "meta":{},
         "mappings":{"items":{"name":"slot","type":"text","default":"9am"}}}""");

    var inputs = StepCompiler.compile("https://x.example.com", List.of(OPEN, choose, dynamic))
        .path("nodes").get(0).path("config").path("inputs");

    assertThat(inputs.get(0).path("options").toString()).isEqualTo("[\"Silver\",\"Gold\",\"Platinum\"]");
    assertThat(inputs.get(1).has("options")).as("dynamic: read from the page each run").isFalse();
  }

  @Test
  void aRowsColumnKeepsItsOwnRules() {
    JsonNode rows = step("""
        {"seq":1,"action":"browser.fillRows","version":1,"label":"Rows",
         "input":{"rowCss":"li","columns":[{"key":"name","label":"Name","required":true},
                  {"key":"size","label":"Size","options":["S","M","L"]}],"rows":[{"name":"a","size":"M"}]},
         "meta":{},
         "mappings":{"rows":{"name":"items","type":"list","default":[{"name":"a","size":"M"}],
                             "maxRows":4}}}""");

    var input = StepCompiler.compile("https://x.example.com", List.of(OPEN, rows))
        .path("nodes").get(0).path("config").path("inputs").get(0);

    assertThat(input.path("columns").get(0).path("required").asBoolean()).isTrue();
    assertThat(input.path("columns").get(1).path("options").size()).isEqualTo(3);
  }
  @Test
  void aStepThatTypesIsBoundToTheSiteItWasRecordedOn() {
    var typed = step("""
        {"seq":1,"action":"browser.setText","version":1,"label":"Name",
         "input":{"element":{"strategies":[{"by":"css","value":"#n"}]},"text":"Chiam"},
         "meta":{"url":"https://Portal.Example.com:8443/login?next=/home"}}""");
    var read = step("""
        {"seq":2,"action":"browser.getText","version":1,"label":"Total",
         "input":{"element":{"strategies":[{"by":"css","value":"#t"}]}},
         "meta":{"url":"https://portal.example.com/"}}""");
    var nodes = StepCompiler.compile("https://portal.example.com", List.of(OPEN, typed, read))
        .path("nodes");
    assertThat(nodes.get(2).path("config").path("inputs").path("expectOrigin").asText())
        .isEqualTo("https://portal.example.com:8443");
    assertThat(nodes.get(3).path("config").path("inputs").has("expectOrigin")).isFalse();
  }

  @Test
  void originOfKeepsOnlyWebAddresses() {
    assertThat(StepCompiler.originOf("https://a.example.com/x")).isEqualTo("https://a.example.com");
    assertThat(StepCompiler.originOf("http://localhost:5173/")).isEqualTo("http://localhost:5173");
    assertThat(StepCompiler.originOf("chrome://settings")).isNull();
    assertThat(StepCompiler.originOf("")).isNull();
    assertThat(StepCompiler.originOf("not a url")).isNull();
  }

  @Test
  void waitForWhatHappensKeepsEachOutcomesRuleBesideTheStepAndReadsBack() {
    JsonNode search = step("""
        {"seq":1,"action":"browser.click","version":2,"label":"Search",
         "input":{"element":{"strategies":[{"by":"css","value":"#go"}]}},"meta":{}}""");
    JsonNode wait = step("""
        {"seq":2,"action":"browser.waitFor","version":1,"label":"Wait for results",
         "input":{"timeoutMs":20000,"outcomes":[
           {"name":"Results","element":{"strategies":[{"by":"css","value":".result"}]}},
           {"name":"Nothing found","text":"No results","then":"stop","message":"Nothing matched"}]},
         "meta":{}}""");
    var graph = StepCompiler.compile("https://portal.example.com", List.of(OPEN, search, wait));
    JsonNode node = null;
    for (JsonNode n : graph.path("nodes")) {
      if ("browser.waitFor".equals(n.path("config").path("action").asText())) node = n;
    }
    assertThat(node).isNotNull();
    JsonNode config = node.path("config");
    // The browser only gets what to look for.
    assertThat(config.path("inputs").path("outcomes").get(1).has("then")).isFalse();
    assertThat(config.path("inputs").path("outcomes").get(1).path("text").asText()).isEqualTo("No results");
    assertThat(config.path("onOutcome").path("Nothing found").path("then").asText()).isEqualTo("stop");
    assertThat(config.path("onOutcome").path("Nothing found").path("message").asText())
        .isEqualTo("Nothing matched");
    assertThat(config.path("onOutcome").path("Results").path("then").asText()).isEqualTo("continue");

    var steps = RecordingGraph.open(graph).steps();
    JsonNode back = steps.stream().filter(st -> "browser.waitFor".equals(st.path("action").asText()))
        .findFirst().orElseThrow();
    assertThat(back.path("input").path("outcomes").get(1).path("then").asText()).isEqualTo("stop");
    assertThat(back.path("input").path("outcomes").get(1).path("message").asText())
        .isEqualTo("Nothing matched");
    assertThat(back.path("meta").has("config")).isFalse();
  }

  @Test
  void waitForWhatHappensNeedsNamedOutcomes() {
    JsonNode none = step("""
        {"seq":1,"action":"browser.waitFor","version":1,"label":"Wait","input":{"outcomes":[]},"meta":{}}""");
    assertThatThrownBy(() -> StepCompiler.compile("https://x.test", List.of(OPEN, none)))
        .isInstanceOf(ValidationException.class);
    JsonNode twice = step("""
        {"seq":1,"action":"browser.waitFor","version":1,"label":"Wait","input":{"outcomes":[
          {"name":"A","text":"x"},{"name":"A","text":"y"}]},"meta":{}}""");
    assertThatThrownBy(() -> StepCompiler.compile("https://x.test", List.of(OPEN, twice)))
        .isInstanceOf(ValidationException.class).hasMessageContaining("own name");
  }

  @Test
  void aStepThatCannotBeUndoneIsMarkedAndReadsBack() {
    JsonNode pay = step("""
        {"seq":1,"action":"browser.click","version":2,"label":"Pay now",
         "input":{"element":{"strategies":[{"by":"css","value":"#pay"}]}},"meta":{"irreversible":true}}""");
    var graph = StepCompiler.compile("https://shop.example.com", List.of(OPEN, pay));
    JsonNode node = null;
    for (JsonNode n : graph.path("nodes")) if ("Pay now".equals(n.path("label").asText())) node = n;
    assertThat(node.path("config").path("irreversible").asBoolean()).isTrue();
    JsonNode back = RecordingGraph.open(graph).steps().stream()
        .filter(st -> "Pay now".equals(st.path("label").asText())).findFirst().orElseThrow();
    assertThat(back.path("meta").path("irreversible").asBoolean()).isTrue();
    assertThat(back.path("meta").has("config")).isFalse();
  }
}
