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
import java.util.ArrayDeque;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Choices made at run time beyond the plain name: several words, a date, the cheapest, a card
 * inside another card, a remembered pick, a suggestion that is not there, and a form the site
 * refused.
 */
class DynamicWebStepsMoreTest {

  private static WorkflowGraph oneStep(String config) {
    return new WorkflowGraph(List.of(
        new WorkflowNode("start", NodeType.TRIGGER, "start", null, null),
        new WorkflowNode("step", NodeType.DEVICE_ACTION, "step", Json.read(config),
            new WorkflowNode.RetryPolicy(1, 1, 1, 10)),
        new WorkflowNode("finish", NodeType.END, "finish", null, null)),
        List.of(new WorkflowEdge("start", "step", null), new WorkflowEdge("step", "finish", null)));
  }

  private static String click(String extra) {
    return """
        {"preset":"web","action":"browser.clickItem","version":1,"target":"sameBrowser",
         "inputs":{"element":{"strategies":[{"by":"text","value":"Ramayana"}],"label":"Movie"},
                   "item":"{{ input.movie }}"%s}}
        """.formatted(extra);
  }

  private static JsonNode listing(String... rows) {
    var items = Json.mapper().createArrayNode();
    for (int i = 0; i < rows.length; i++) {
      String[] parts = rows[i].split("\\|", 2);
      items.addObject().put("index", i).put("label", parts[0])
          .put("text", parts.length > 1 ? parts[1] : parts[0]);
    }
    return Json.obj().put("count", rows.length).set("items", items);
  }

  private static EngineHarness harness(String config) {
    EngineHarness harness = new EngineHarness(oneStep(config));
    String element = "\"element\":{\"$ref\":\"device-actions.json#/schemas/locatorBundle\"}";
    harness.deviceInputSchemas.put("browser.clickItem", Json.read("{\"type\":\"object\","
        + "\"properties\":{" + element + ",\"item\":{\"type\":\"string\"},"
        + "\"by\":{\"enum\":[\"text\",\"index\"]},\"within\":{\"type\":\"object\"},"
        + "\"prefer\":{\"enum\":[\"ask\",\"first\",\"lowestPrice\",\"highestPrice\"]}},"
        + "\"required\":[\"element\"]}"));
    harness.deviceInputSchemas.put("browser.chooseOption", Json.read("{\"type\":\"object\","
        + "\"properties\":{" + element + ",\"text\":{\"type\":\"string\"},"
        + "\"option\":{\"type\":\"string\"}},\"required\":[\"element\",\"text\"]}"));
    harness.deviceInputSchemas.put("browser.fillForm", Json.read("{\"type\":\"object\","
        + "\"properties\":{\"fields\":{\"type\":\"array\"},\"submit\":{\"type\":\"object\"}},"
        + "\"required\":[\"fields\"]}"));
    harness.deviceOutputs.put("browser.clickItem", Json.obj().put("clicked", true));
    return harness;
  }

  private static JsonNode form(EngineHarness harness) {
    return harness.raised.get(harness.raised.size() - 1).context().path("form");
  }

  private static JsonNode field(EngineHarness harness, String key) {
    for (JsonNode f : form(harness).path("fields")) if (key.equals(f.path("key").asText())) return f;
    return null;
  }

  private static String clicked(EngineHarness harness) {
    return harness.deviceRequests.stream().filter(r -> r.action().equals("browser.clickItem"))
        .reduce((a, b) -> b).orElseThrow().input().path("item").asText();
  }

  @Test
  void severalWordsMustAllBeInTheCard() {
    EngineHarness harness = harness(click(""));
    harness.toolRule = AutonomyRule.JIZZA;
    harness.deviceOutputs.put("browser.listItems", listing("Ramayana|Ramayana Tamil 2D",
        "Ramayana |Ramayana Hindi 3D", "Dune|Dune English"));

    var run = harness.engine.start(harness.workflow.getId(), 1,
        Json.obj().put("movie", "Ramayana, Hindi, 3D"), "manual");

    assertThat(run.status()).isEqualTo(RunStatus.DONE);
    // Two cards are called "Ramayana": the one meant is clicked by its place in the list.
    var sent = harness.deviceRequests.get(harness.deviceRequests.size() - 1).input();
    assertThat(sent.path("by").asText()).isEqualTo("index");
    assertThat(sent.path("item").asText()).isEqualTo("1");
  }

  @Test
  void aTieIsAskedWithTheItemsThatTiedFirst() {
    EngineHarness harness = harness(click(""));
    harness.toolRule = AutonomyRule.JIZZA;
    harness.deviceOutputs.put("browser.listItems", listing("Kantara",
        "Ramayana (Hindi)", "Ramayana (Tamil)"));

    var run = harness.engine.start(harness.workflow.getId(), 1,
        Json.obj().put("movie", "Ramayana"), "manual");

    assertThat(run.status()).isEqualTo(RunStatus.PAUSED_WAITING_INSTRUCTION);
    assertThat(field(harness, "item").path("options").toString())
        .isEqualTo("[\"Ramayana (Hindi)\",\"Ramayana (Tamil)\",\"Kantara\"]");
    assertThat(harness.raised.get(0).body()).contains("Several items match \"Ramayana\"");
  }

  @Test
  void preferTheCheapestSettlesATie() {
    EngineHarness harness = harness(click(",\"prefer\":\"lowestPrice\""));
    harness.toolRule = AutonomyRule.JIZZA;
    harness.deviceOutputs.put("browser.listItems", listing("PVR 7 PM|Ramayana ₹ 350",
        "INOX 8 PM|Ramayana ₹ 220", "Cinepolis|Ramayana Rs. 1,200"));

    var run = harness.engine.start(harness.workflow.getId(), 1,
        Json.obj().put("movie", "Ramayana"), "manual");

    assertThat(run.status()).isEqualTo(RunStatus.DONE);
    assertThat(clicked(harness)).isEqualTo("INOX 8 PM");
  }

  @Test
  void aDateFindsItsDayInADateStrip() {
    EngineHarness harness = harness(click(""));
    harness.toolRule = AutonomyRule.JIZZA;
    harness.deviceOutputs.put("browser.listItems", listing("Sat 08 Nov", "Sun 09 Nov",
        "Mon 10 Nov"));

    var run = harness.engine.start(harness.workflow.getId(), 1,
        Json.obj().put("movie", "2026-11-09"), "manual");

    assertThat(run.status()).isEqualTo(RunStatus.DONE);
    assertThat(clicked(harness)).isEqualTo("Sun 09 Nov");
  }

  @Test
  void aShowtimeIsChosenInsideTheTheatreChosenFirst() {
    EngineHarness harness = harness("""
        {"preset":"web","action":"browser.clickItem","version":1,"target":"sameBrowser",
         "inputs":{"element":{"strategies":[{"by":"text","value":"7:00 PM"}],"label":"Showtime"},
                   "item":"{{ input.time }}",
                   "within":{"element":{"strategies":[{"by":"css","value":".venue"}],
                             "label":"Theatre"},"item":"{{ input.theatre }}"}}}
        """);
    harness.toolRule = AutonomyRule.JIZZA;
    harness.deviceOutputQueues.put("browser.listItems", new ArrayDeque<>(List.of(
        listing("PVR Phoenix", "INOX Nexus"), listing("10:00 AM", "07:30 PM"))));

    var run = harness.engine.start(harness.workflow.getId(), 1,
        Json.obj().put("theatre", "inox").put("time", "07:30 PM"), "manual");

    assertThat(run.status()).isEqualTo(RunStatus.DONE);
    var lists = harness.deviceRequests.stream()
        .filter(r -> r.action().equals("browser.listItems")).toList();
    assertThat(lists).hasSize(2);
    assertThat(lists.get(0).input().path("element").path("label").asText()).isEqualTo("Theatre");
    assertThat(lists.get(1).input().path("within").path("item").asText()).isEqualTo("INOX Nexus");
    var sent = harness.deviceRequests.get(harness.deviceRequests.size() - 1).input();
    assertThat(sent.path("item").asText()).isEqualTo("07:30 PM");
    assertThat(sent.path("within").path("item").asText()).isEqualTo("INOX Nexus");
  }

  @Test
  void aMarkedListReadsTheatresAndShowtimesWithTheirOwnPatterns() {
    EngineHarness harness = harness("""
        {"preset":"web","action":"browser.clickItem","version":1,"target":"sameBrowser",
         "inputs":{"element":{"strategies":[{"by":"text","value":"10:45 PM"}],"label":"Showtime"},
                   "item":"{{ input.time }}","itemCss":"div.times > div",
                   "within":{"element":{"strategies":[{"by":"css","value":".venue"}],
                             "label":"Theatre"},"item":"{{ input.theatre }}",
                             "itemCss":"div.venue"}}}
        """);
    harness.toolRule = AutonomyRule.JIZZA;
    harness.deviceOutputQueues.put("browser.listItems", new ArrayDeque<>(List.of(
        listing("Rakki RGB Laser 4K", "Meenakshi Cinemas"), listing("03:40 PM", "07:15 PM"))));

    var run = harness.engine.start(harness.workflow.getId(), 1,
        Json.obj().put("theatre", "meenakshi").put("time", "07:15 PM"), "manual");

    assertThat(run.status()).isEqualTo(RunStatus.DONE);
    var lists = harness.deviceRequests.stream()
        .filter(r -> r.action().equals("browser.listItems")).toList();
    // The theatres are read with the card pattern, the showtimes with the item pattern.
    assertThat(lists.get(0).input().path("itemCss").asText()).isEqualTo("div.venue");
    assertThat(lists.get(1).input().path("itemCss").asText()).isEqualTo("div.times > div");
    assertThat(lists.get(1).input().path("within").path("itemCss").asText())
        .isEqualTo("div.venue");
    var sent = harness.deviceRequests.get(harness.deviceRequests.size() - 1).input();
    assertThat(sent.path("itemCss").asText()).isEqualTo("div.times > div");
    assertThat(sent.path("within").path("item").asText()).isEqualTo("Meenakshi Cinemas");
  }

  @Test
  void theCardLetsThePersonSwitchTheatreAndShowsThatTheatresShowtimes() {
    EngineHarness harness = harness("""
        {"preset":"web","action":"browser.clickItem","version":1,"target":"sameBrowser",
         "inputs":{"element":{"strategies":[{"by":"text","value":"07:20 PM"}],"label":"Showtime"},
                   "item":"{{ input.time }}",
                   "within":{"element":{"strategies":[{"by":"css","value":".venue"}],
                             "label":"INOX: LUXE Phoenix Market City, VelacheryCancellation available"},
                             "item":"{{ input.theatre }}"}}}
        """);
    harness.toolRule = AutonomyRule.ASK;
    var theatres = listing("PVR Phoenix", "INOX Nexus");
    var inox = (com.fasterxml.jackson.databind.node.ObjectNode) listing("11:30 AM", "07:30 PM");
    var groups = inox.putArray("groups");
    groups.addObject().put("index", 0).put("label", "PVR Phoenix")
        .set("items", listing("10:00 AM", "07:00 PM").get("items"));
    groups.addObject().put("index", 1).put("label", "INOX Nexus")
        .set("items", listing("11:30 AM", "07:30 PM").get("items"));
    harness.deviceOutputQueues.put("browser.listItems", new ArrayDeque<>(List.of(
        theatres, inox, theatres, listing("10:00 AM", "07:00 PM"))));

    var run = harness.engine.start(harness.workflow.getId(), 1,
        Json.obj().put("theatre", "INOX Nexus").put("time", "07:30 PM"), "manual");

    assertThat(run.status()).isEqualTo(RunStatus.PAUSED_WAITING_INSTRUCTION);
    var lists = harness.deviceRequests.stream()
        .filter(r -> r.action().equals("browser.listItems")).toList();
    assertThat(lists.get(1).input().path("groups").asBoolean()).isTrue();
    // The theatre stays a choice of the page's theatres, its long recorded label shortened.
    JsonNode theatre = field(harness, "within.item");
    assertThat(theatre.path("type").asText()).isEqualTo("select");
    assertThat(theatre.path("label").asText()).isEqualTo("Card to choose in");
    assertThat(theatre.path("value").asText()).isEqualTo("INOX Nexus");
    assertThat(theatre.path("options").toString()).isEqualTo("[\"PVR Phoenix\",\"INOX Nexus\"]");
    JsonNode time = field(harness, "item");
    assertThat(time.path("dependsOn").asText()).isEqualTo("within.item");
    assertThat(time.path("optionsBy").path("PVR Phoenix").toString())
        .isEqualTo("[\"10:00 AM\",\"07:00 PM\"]");
    assertThat(time.path("optionsBy").path("INOX Nexus").toString())
        .isEqualTo("[\"11:30 AM\",\"07:30 PM\"]");

    harness.engine.resume(run.id(), Json.obj().put("approved", true).set("inputs",
        Json.obj().put("within.item", "PVR Phoenix").put("item", "07:00 PM")));
    var sent = harness.deviceRequests.get(harness.deviceRequests.size() - 1);
    assertThat(sent.action()).isEqualTo("browser.clickItem");
    assertThat(sent.input().path("within").path("item").asText()).isEqualTo("PVR Phoenix");
    assertThat(sent.input().path("item").asText()).isEqualTo("07:00 PM");
  }

  @Test
  void aTheatreNotOnThePageIsAskedWithTheShowtimesOfEveryTheatre() {
    EngineHarness harness = harness("""
        {"preset":"web","action":"browser.clickItem","version":1,"target":"sameBrowser",
         "inputs":{"element":{"strategies":[{"by":"text","value":"07:20 PM"}],"label":"Showtime"},
                   "item":"{{ input.time }}",
                   "within":{"element":{"strategies":[{"by":"css","value":".venue"}],
                             "label":"Theatres"},
                             "item":"{{ input.theatre }}"}}}
        """);
    harness.toolRule = AutonomyRule.JIZZA;
    var theatres = listing("PVR Phoenix", "INOX Nexus");
    var first = (com.fasterxml.jackson.databind.node.ObjectNode) listing("10:00 AM", "07:00 PM");
    var groups = first.putArray("groups");
    groups.addObject().put("index", 0).put("label", "PVR Phoenix")
        .set("items", listing("10:00 AM", "07:00 PM").get("items"));
    groups.addObject().put("index", 1).put("label", "INOX Nexus")
        .set("items", listing("11:30 AM", "06:30 PM").get("items"));
    harness.deviceOutputQueues.put("browser.listItems", new ArrayDeque<>(List.of(
        theatres, first, theatres, listing("11:30 AM", "06:30 PM"))));

    var run = harness.engine.start(harness.workflow.getId(), 1,
        Json.obj().put("theatre", "Rakki Cinemas").put("time", "06:30 PM"), "manual");

    // One question: the theatre from the page's theatres, the showtime following it.
    assertThat(run.status()).isEqualTo(RunStatus.PAUSED_WAITING_INSTRUCTION);
    var lists = harness.deviceRequests.stream()
        .filter(r -> r.action().equals("browser.listItems")).toList();
    assertThat(lists).hasSize(2);
    assertThat(lists.get(1).input().path("groups").asBoolean()).isTrue();
    JsonNode theatre = field(harness, "within.item");
    assertThat(theatre.path("type").asText()).isEqualTo("select");
    assertThat(theatre.path("options").toString()).isEqualTo("[\"PVR Phoenix\",\"INOX Nexus\"]");
    JsonNode time = field(harness, "item");
    assertThat(time.path("dependsOn").asText()).isEqualTo("within.item");
    assertThat(time.path("optionsBy").path("INOX Nexus").toString())
        .isEqualTo("[\"11:30 AM\",\"06:30 PM\"]");

    harness.engine.resume(run.id(), Json.obj().put("approved", true).set("inputs",
        Json.obj().put("within.item", "INOX Nexus").put("item", "06:30 PM")));
    var sent = harness.deviceRequests.get(harness.deviceRequests.size() - 1);
    assertThat(sent.action()).isEqualTo("browser.clickItem");
    assertThat(sent.input().path("within").path("item").asText()).isEqualTo("INOX Nexus");
    assertThat(sent.input().path("item").asText()).isEqualTo("06:30 PM");
  }

  @Test
  void noTheatreGivenAsksWithTheTheatresAndShowtimesThePageListsNow() {
    EngineHarness harness = harness("""
        {"preset":"web","action":"browser.clickItem","version":1,"target":"sameBrowser",
         "inputs":{"element":{"strategies":[{"by":"text","value":"07:20 PM"}],"label":"Showtime"},
                   "item":"{{ input.time }}",
                   "within":{"element":{"strategies":[{"by":"css","value":".venue"}],
                             "label":"Theatres"},
                             "item":"{{ input.theatre }}"}}}
        """);
    harness.toolRule = AutonomyRule.JIZZA;
    var first = (com.fasterxml.jackson.databind.node.ObjectNode) listing("10:00 AM");
    var groups = first.putArray("groups");
    groups.addObject().put("index", 0).put("label", "Kamala")
        .set("items", listing("10:00 AM").get("items"));
    groups.addObject().put("index", 1).put("label", "Sathyam")
        .set("items", listing("02:00 PM", "10:00 PM").get("items"));
    harness.deviceOutputQueues.put("browser.listItems", new ArrayDeque<>(List.of(
        listing("Kamala", "Sathyam"), first)));

    var run = harness.engine.start(harness.workflow.getId(), 1,
        Json.obj().put("theatre", "").put("time", ""), "manual");

    assertThat(run.status()).isEqualTo(RunStatus.PAUSED_WAITING_INSTRUCTION);
    assertThat(field(harness, "within.item").path("options").toString())
        .isEqualTo("[\"Kamala\",\"Sathyam\"]");
    assertThat(field(harness, "item").path("optionsBy").path("Sathyam").toString())
        .isEqualTo("[\"02:00 PM\",\"10:00 PM\"]");
  }

  @Test
  void aTheatreNotOnThePageIsStillAskedWhenItsShowtimesCannotBeRead() {
    EngineHarness harness = harness("""
        {"preset":"web","action":"browser.clickItem","version":1,"target":"sameBrowser",
         "inputs":{"element":{"strategies":[{"by":"text","value":"07:20 PM"}],"label":"Showtime"},
                   "item":"{{ input.time }}",
                   "within":{"element":{"strategies":[{"by":"css","value":".venue"}],
                             "label":"Theatres"},
                             "item":"{{ input.theatre }}"}}}
        """);
    harness.toolRule = AutonomyRule.JIZZA;
    harness.deviceOutputQueues.put("browser.listItems", new ArrayDeque<>(List.of(
        listing("PVR Phoenix", "INOX Nexus"), listing())));

    var run = harness.engine.start(harness.workflow.getId(), 1,
        Json.obj().put("theatre", "Rakki Cinemas").put("time", "06:30 PM"), "manual");

    assertThat(run.status()).isEqualTo(RunStatus.PAUSED_WAITING_INSTRUCTION);
    JsonNode theatre = field(harness, "within.item");
    assertThat(theatre.path("type").asText()).isEqualTo("select");
    assertThat(theatre.path("options").toString()).isEqualTo("[\"PVR Phoenix\",\"INOX Nexus\"]");
  }

  @Test
  void theNamesGivenWhileRecordingLabelTheCardAndReadTheCardsByTheirNamePattern() {
    EngineHarness harness = harness("""
        {"preset":"web","action":"browser.clickItem","version":1,"target":"sameBrowser",
         "list":{"named":true,"title":"Showtime","withinTitle":"Theatre"},
         "inputs":{"element":{"strategies":[{"by":"text","value":"07:20 PM"}]},
                   "item":"{{ input.time }}","titleCss":"span.time",
                   "within":{"element":{"strategies":[{"by":"css","value":".venue"}]},
                             "item":"{{ input.theatre }}","itemCss":"div.venue","titleCss":"h3"}}}
        """);
    harness.toolRule = AutonomyRule.ASK;
    var theatres = listing("PVR Phoenix", "INOX Nexus");
    harness.deviceOutputQueues.put("browser.listItems", new ArrayDeque<>(List.of(
        theatres, listing("11:30 AM", "07:30 PM"))));

    var run = harness.engine.start(harness.workflow.getId(), 1,
        Json.obj().put("theatre", "INOX Nexus").put("time", "07:30 PM"), "manual");

    assertThat(run.status()).isEqualTo(RunStatus.PAUSED_WAITING_INSTRUCTION);
    var lists = harness.deviceRequests.stream()
        .filter(r -> r.action().equals("browser.listItems")).toList();
    // The theatres are read with the theatres' own name pattern, the showtimes with theirs.
    assertThat(lists.get(0).input().path("titleCss").asText()).isEqualTo("h3");
    assertThat(lists.get(1).input().path("titleCss").asText()).isEqualTo("span.time");
    assertThat(field(harness, "within.item").path("label").asText()).isEqualTo("Theatre");
    assertThat(field(harness, "item").path("label").asText()).isEqualTo("Showtime");
  }

  @Test
  void aRememberedPickIsUsedNextTimeWithoutAsking() {
    EngineHarness harness = harness(click(""));
    harness.toolRule = AutonomyRule.JIZZA;
    harness.deviceOutputs.put("browser.listItems", listing("Kantara", "Raamayanam 3D"));

    var run = harness.engine.start(harness.workflow.getId(), 1,
        Json.obj().put("movie", "Ramayana"), "manual");
    assertThat(run.status()).isEqualTo(RunStatus.PAUSED_WAITING_INSTRUCTION);
    assertThat(field(harness, "_remember")).isNotNull();

    harness.engine.resume(run.id(), Json.obj().put("approved", true).set("inputs",
        Json.obj().put("item", "Raamayanam 3D").put("_remember", true)));
    assertThat(clicked(harness)).isEqualTo("Raamayanam 3D");
    assertThat(harness.stored).hasSize(1);

    int raised = harness.raised.size();
    var again = harness.engine.start(harness.workflow.getId(), 1,
        Json.obj().put("movie", "ramayana"), "manual");
    assertThat(again.status()).isEqualTo(RunStatus.DONE);
    assertThat(harness.raised).hasSize(raised);
    assertThat(clicked(harness)).isEqualTo("Raamayanam 3D");
  }

  @Test
  void aSuggestionThatIsNotThereIsChosenFromWhatIs() {
    EngineHarness harness = harness("""
        {"preset":"web","action":"browser.chooseOption","version":1,"target":"sameBrowser",
         "inputs":{"element":{"strategies":[{"by":"css","value":"#city"}],"label":"City"},
                   "text":"{{ input.city }}"}}
        """);
    harness.toolRule = AutonomyRule.ALLOW;
    harness.deviceFailures.put("browser.chooseOption", Json.read("""
        {"error":{"kind":"item_not_found","message":"No suggestion matches",
                  "items":["Bengaluru","Bengaluru Rural"]}}"""));

    var run = harness.engine.start(harness.workflow.getId(), 1,
        Json.obj().put("city", "Bangalore"), "manual");

    assertThat(run.status()).isEqualTo(RunStatus.PAUSED_WAITING_INSTRUCTION);
    JsonNode option = field(harness, "option");
    assertThat(option.path("type").asText()).isEqualTo("select");
    assertThat(option.path("options").size()).isEqualTo(2);

    harness.deviceOutputs.put("browser.chooseOption", Json.obj().put("chosen", "Bengaluru"));
    harness.engine.resume(run.id(), Json.obj().put("approved", true)
        .set("inputs", Json.obj().put("option", "Bengaluru")));
    var sent = harness.deviceRequests.get(harness.deviceRequests.size() - 1);
    assertThat(sent.input().path("option").asText()).isEqualTo("Bengaluru");
    assertThat(sent.idempotencyKey()).endsWith(":s1");
  }

  @Test
  void theSitesErrorsComeBackToTheCardAndTheFixIsSent() {
    EngineHarness harness = harness("""
        {"preset":"web","action":"browser.fillForm","version":1,"target":"sameBrowser",
         "inputs":{"fields":[
           {"element":{"strategies":[{"by":"css","value":"#n"}],"label":"Name"},"kind":"text",
            "value":"{{ input.name }}"},
           {"element":{"strategies":[{"by":"css","value":"#p"}],"label":"Phone"},"kind":"text",
            "value":"{{ input.phone }}"}],
          "submit":{"strategies":[{"by":"css","value":"#go"}]}}}
        """);
    harness.toolRule = AutonomyRule.ALLOW;
    harness.deviceOutputQueues.put("browser.fillForm", new ArrayDeque<>(List.of(
        Json.read("{\"count\":2,\"submitted\":true,\"errors\":[{\"field\":1,\"label\":\"Phone\","
            + "\"message\":\"Enter a 10-digit number\"}]}"),
        Json.read("{\"count\":2,\"submitted\":true,\"errors\":[]}"))));

    var run = harness.engine.start(harness.workflow.getId(), 1,
        Json.obj().put("name", "Asha").put("phone", "123"), "manual");

    assertThat(run.status()).isEqualTo(RunStatus.PAUSED_WAITING_INSTRUCTION);
    assertThat(field(harness, "fields.1.value").path("error").asText())
        .isEqualTo("Enter a 10-digit number");
    assertThat(harness.raised.get(harness.raised.size() - 1).body())
        .contains("The site did not accept the form");

    harness.engine.resume(run.id(), Json.obj().put("approved", true)
        .set("inputs", Json.obj().put("fields.1.value", "9876543210")));

    assertThat(harness.deviceRequests).hasSize(2);
    JsonNode fields = harness.deviceRequests.get(1).input().path("fields");
    assertThat(fields.get(0).path("value").asText()).isEqualTo("Asha");
    assertThat(fields.get(1).path("value").asText()).isEqualTo("9876543210");
  }
}
