package com.jizza.app.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.enums.WorkflowStatus;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.persistence.repository.WorkflowRepository;
import org.springframework.beans.factory.annotation.Autowired;
import com.jizza.devicesim.FakeBrowser;
import com.jizza.devicesim.SimDevice;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

/** Browser discovery and literal step testing through the whole permission and device stack. */
@org.junit.jupiter.api.condition.EnabledIf(
    value = "com.jizza.engine.persistence.support.TestDatabase#available",
    disabledReason = "no PostgreSQL: set JIZZA_TEST_DB_URL or start Docker")
class WebAutomationE2eTest extends E2eSupport {

  private static final String PASSWORD = "Correct-Horse-92";

  @LocalServerPort private int port;

  private ApiClient api;
  private SimDevice device;
  private FakeBrowser fake;

  @Autowired private WorkflowRepository workflows;
  @Autowired private E2eSupport.ScriptedLlmClient model;
  @Autowired private com.jizza.engine.persistence.repository.JobRepository jobs;
  @Autowired private com.jizza.engine.devices.events.DeviceEventIntake events;

  @BeforeEach
  void setUp() {
    api = signedIn("web");
    device = new SimDevice("http://localhost:" + port);
    fake = new FakeBrowser(device);
    fake.page("https://example.com").title("Example").element("Sign in", "button", "Sign in");
    fake.page("https://example.com/b").title("Example B").element("Sign in", "button", "Sign in");
    device.keyStorage("browser");
    String code = api.post("/api/devices/pairings", Map.of()).json().path("code").asText();
    device.enrollWithCode(code, "Chrome on MacBook", "browser");
    device.signIn();
    device.connect();
  }

  @AfterEach
  void tearDown() {
    device.close();
  }

  @Test
  void listsBrowserAgentsAndTestsALiteralStep() {
    JsonNode browsers = api.get("/api/web/browsers").json();
    assertThat(browsers).hasSize(1);
    assertThat(browsers.get(0).path("id").asText()).isEqualTo(device.deviceId().toString());
    assertThat(browsers.get(0).path("family").asText()).isEqualTo("chrome");
    assertThat(browsers.get(0).path("allowedSites").toString()).contains("*");

    var tested = api.post("/api/web/test-step", Map.of(
        "deviceId", device.deviceId(),
        "action", "browser.open",
        "version", 2,
        "inputs", Map.of("url", "https://example.com")));
    assertThat(tested.ok()).as(tested.body()).isTrue();
    assertThat(tested.json().path("status").asText()).isEqualTo("succeeded");
    assertThat(tested.json().path("output").path("title").asText()).isEqualTo("Example");
    assertThat(tested.json().path("output").path("sessionId").asText()).startsWith("s_");
  }

  @Test
  void pickReturnsTheElementOnlyToItsOwner() throws Exception {
    var started = api.post("/api/web/pick", Map.of("deviceId", device.deviceId(),
        "url", "https://example.com"));
    assertThat(started.ok()).as(started.body()).isTrue();
    String commandId = started.json().path("commandId").asText();

    JsonNode result = null;
    for (int i = 0; i < 40; i++) {
      result = api.get("/api/web/pick/" + commandId).json();
      if (!"waiting".equals(result.path("status").asText())) break;
      Thread.sleep(250);
    }
    assertThat(result.path("status").asText()).as(result.toString()).isEqualTo("done");
    assertThat(result.path("element").path("strategies")).isNotEmpty();
    assertThat(result.path("url").asText()).isEqualTo("https://example.com");

    assertThat(signedIn("web-other").get("/api/web/pick/" + commandId).status()).isEqualTo(404);
  }

  /** The e2e profile runs without the job queue, so the test hands device events to the intake itself. */
  private void drainDeviceEvents() {
    jobs.findByStatus(com.jizza.engine.api.enums.JobStatus.READY).stream()
        .filter(j -> com.jizza.engine.api.queue.JobTypes.DEVICE_EVENT.equals(j.getType()))
        .sorted(java.util.Comparator.comparing(com.jizza.engine.persistence.entity.JobEntity::getRunAfter))
        .forEach(j -> {
          events.handle(j.getPayload());
          j.setStatus(com.jizza.engine.api.enums.JobStatus.DONE);
          jobs.save(j);
        });
  }

  private static ObjectNode withElement(ObjectNode input) {
    input.set("element", Json.read(
        "{\"strategies\":[{\"by\":\"role\",\"role\":\"button\",\"name\":\"Sign in\"}]}"));
    return input;
  }

  @Test
  void aRecordingIsReviewedSavedAndRunAsAModuleTwice() throws Exception {
    fake.recordedStep("browser.open", 2, "Open the portal",
            Json.obj().put("url", "https://example.com"), false)
        .recordedStep("browser.setText", 1, "Set password",
            withElement(Json.obj().put("text", "hunter2")), true)
        .recordedStep("browser.getText", 1, "Heading", withElement(Json.obj()), false);

    var started = api.post("/api/web/recordings", Map.of("deviceId", device.deviceId(),
        "url", "https://example.com", "name", "Read heading"));
    assertThat(started.ok()).as(started.body()).isTrue();
    String id = started.json().path("id").asText();

    JsonNode recording = null;
    for (int i = 0; i < 80; i++) {
      drainDeviceEvents();
      recording = api.get("/api/web/recordings/" + id).json();
      if ("review".equals(recording.path("status").asText())
          && recording.path("steps").size() == 3) break;
      Thread.sleep(250);
    }
    assertThat(recording.path("status").asText()).as(recording.toString()).isEqualTo("review");
    assertThat(recording.path("steps")).hasSize(3);
    assertThat(recording.toString()).doesNotContain("hunter2");

    // The password step needs a saved login; saving is refused until it has one.
    var refused = api.post("/api/web/recordings/" + id + "/save", Map.of("name", "Read heading"));
    assertThat(refused.status()).isEqualTo(400);
    assertThat(refused.body()).contains("Choose a saved login for the password step");

    var edited = api.patch("/api/web/recordings/" + id + "/steps", Map.of("ops", java.util.List.of(
        Map.of("op", "delete", "seq", 1),
        Map.of("op", "map", "seq", 0, "field", "url",
            "input", Map.of("name", "page", "type", "text", "default", "https://example.com")))));
    assertThat(edited.ok()).as(edited.body()).isTrue();

    var saved = api.post("/api/web/recordings/" + id + "/save", Map.of("name", "Read heading"));
    assertThat(saved.ok()).as(saved.body()).isTrue();
    java.util.UUID workflowId = java.util.UUID.fromString(saved.json().path("workflowId").asText());
    var entity = workflows.findById(workflowId).orElseThrow();
    assertThat(entity.getOrigin().name()).isEqualTo("RECORDED");
    entity.setStatus(WorkflowStatus.APPROVED);
    workflows.save(entity);

    for (String page : new String[] {"https://example.com", "https://example.com/b"}) {
      var run = api.post("/api/workflows/" + workflowId + "/run",
          Map.of("version", 1, "input", Json.obj().put("page", page)));
      assertThat(run.ok()).as(run.body()).isTrue();
      JsonNode finished = run.json();
      for (int i = 0; i < 80 && !"DONE".equals(finished.path("status").asText())
          && !"FAILED".equals(finished.path("status").asText()); i++) {
        drainDeviceEvents();
        Thread.sleep(250);
        finished = api.get("/api/runs/" + run.json().path("id").asText()).json();
      }
      assertThat(finished.path("status").asText()).as(finished.toString()).isEqualTo("DONE");
      assertThat(device.received().toString()).contains(page);
    }
    assertThat(api.get("/api/web/recordings/" + id).json().path("status").asText())
        .isEqualTo("saved");
  }

  @Test
  void anotherAccountCannotSeeOrChangeARecording() throws Exception {
    var started = api.post("/api/web/recordings", Map.of("deviceId", device.deviceId(),
        "url", "https://example.com", "name", "Mine"));
    String id = started.json().path("id").asText();
    ApiClient other = signedIn("web-other");
    assertThat(other.get("/api/web/recordings/" + id).status()).isEqualTo(404);
    assertThat(other.delete("/api/web/recordings/" + id).status()).isEqualTo(404);
    assertThat(other.get("/api/web/recordings").json()).isEmpty();
  }

  @Test
  void webRecordInChatStartsARecordingAndShowsStopAndReview() {
    model.reset();
    model.scriptFor("Jizza",
        new com.jizza.engine.api.llm.LlmResponse("", java.util.List.of(
            new com.jizza.engine.api.tool.ToolCall("w1", "web_record", Json.obj()
                .put("url", "https://example.com").put("name", "Read heading"))),
            "tool_use", 5, 5, "m"),
        com.jizza.engine.api.llm.LlmResponse.text("Do the task in Chrome, then press Stop."));

    var reply = api.post("/api/chat", Map.of("message", "Let me show you how I read the heading"));

    assertThat(reply.ok()).as(reply.body()).isTrue();
    assertThat(reply.json().path("actions").toString()).contains("show_steps").contains("Stop")
        .contains("Open review").contains("Chrome on MacBook");
    JsonNode drafts = api.get("/api/web/recordings").json();
    assertThat(drafts).hasSize(1);
    assertThat(drafts.get(0).path("status").asText()).isEqualTo("recording");
  }

  @Test
  void aClickInChatAsksForApprovalEvenWhereARuleAllowsIt() {
    api.put("/api/autonomy/policies", Map.of("toolRef", "device_*", "rule", "ALLOW", "precedence", 50));
    api.post("/api/auth/step-up", Map.of("password", PASSWORD));
    model.reset();
    ObjectNode click = Json.obj();
    click.putObject("element").putArray("strategies").addObject().put("by", "role")
        .put("role", "button").put("name", "Sign in");
    model.scriptFor("Jizza",
        new com.jizza.engine.api.llm.LlmResponse("", java.util.List.of(
            new com.jizza.engine.api.tool.ToolCall("w1", "device_chrome_on_macbook__browser_click", click)),
            "tool_use", 5, 5, "m"),
        com.jizza.engine.api.llm.LlmResponse.text("It needs your approval first."));

    var reply = api.post("/api/chat", Map.of("message", "Click Sign in on the page"));

    assertThat(reply.ok()).as(reply.body()).isTrue();
    assertThat(device.received().toString()).as("nothing reached the browser").doesNotContain("browser.click");
    assertThat(api.get("/api/notifications").json().toString()).containsIgnoringCase("approv");
  }

  @Test
  void anotherAccountCannotTestOnThisBrowser() {
    ApiClient other = signedIn("web-other");
    var tested = other.post("/api/web/test-step", Map.of(
        "deviceId", device.deviceId(),
        "action", "browser.open",
        "version", 2,
        "inputs", Map.of("url", "https://example.com")));
    assertThat(tested.status()).isEqualTo(404);
  }

  @Test
  void aPageChangeOffersAFixAndTheFixedVersionPasses() throws Exception {
    JsonNode graph = Json.read("""
        {"nodes":[
          {"id":"start","type":"TRIGGER","label":"Start","config":{}},
          {"id":"open","type":"DEVICE_ACTION","label":"Open the portal","config":{"preset":"web",
            "action":"browser.open","version":2,"target":"sameBrowser",
            "inputs":{"url":"https://example.com"}}},
          {"id":"read","type":"DEVICE_ACTION","label":"Read the sign-in button","config":{
            "preset":"web","action":"browser.getText","version":1,"target":"sameBrowser",
            "inputs":{"element":{"strategies":[{"by":"role","role":"button","name":"Sign in"}],
              "fingerprint":{"tag":"button","text":"Sign in"},"label":"Sign in button"}}}},
          {"id":"end","type":"END","label":"Done","config":{}}],
         "edges":[{"from":"start","to":"open"},{"from":"open","to":"read"},{"from":"read","to":"end"}]}
        """);
    var saved = api.post("/api/workflows", Map.of("name", "Read sign-in", "graph", graph));
    assertThat(saved.ok()).as(saved.body()).isTrue();
    String workflowId = saved.json().path("id").asText();

    // The site renamed its button.
    fake.page("https://example.com").remove("Sign in").element("Log in", "button", "Log in")
        .text("Log in");
    JsonNode failed = runToEnd(workflowId, 1);
    assertThat(failed.path("status").asText()).as(failed.toString()).isNotEqualTo("DONE");

    JsonNode fixes = api.get("/api/web/fixes?status=open").json();
    assertThat(fixes).as(fixes.toString()).hasSize(1);
    JsonNode fix = fixes.get(0);
    assertThat(fix.path("nodeId").asText()).isEqualTo("read");
    assertThat(fix.path("workflowName").asText()).isEqualTo("Read sign-in");
    assertThat(fix.path("candidates").get(0).path("text").asText()).isEqualTo("Log in");
    assertThat(api.get("/api/notifications").body()).contains("could not find Sign in button");
    JsonNode trace = api.get("/api/runs/" + failed.path("id").asText() + "/trace").json();
    assertThat(trace.toString()).contains("element_not_found");

    ApiClient other = signedIn("web-other");
    assertThat(other.get("/api/web/fixes").json()).isEmpty();
    assertThat(other.post("/api/web/fixes/" + fix.path("id").asText() + "/apply",
        Map.of("candidateIndex", 0)).status()).isEqualTo(404);
    assertThat(other.post("/api/web/fixes/" + fix.path("id").asText() + "/dismiss", Map.of())
        .status()).isEqualTo(404);

    var applied = api.post("/api/web/fixes/" + fix.path("id").asText() + "/apply",
        Map.of("candidateIndex", 0));
    assertThat(applied.ok()).as(applied.body()).isTrue();
    assertThat(applied.json().path("version").asInt()).isEqualTo(2);
    JsonNode element = api.get("/api/workflows/" + workflowId).json().path("graph").path("nodes")
        .get(2).path("config").path("inputs").path("element");
    assertThat(element.path("strategies").get(0).path("name").asText()).isEqualTo("Log in");
    assertThat(element.path("strategies").get(1).path("name").asText()).isEqualTo("Sign in");
    assertThat(api.get("/api/web/fixes?status=open").json()).isEmpty();

    JsonNode passed = runToEnd(workflowId, 2);
    assertThat(passed.path("status").asText()).as(passed.toString()).isEqualTo("DONE");
  }

  @Test
  void aDismissedFixIsClosed() throws Exception {
    JsonNode graph = Json.read("""
        {"nodes":[
          {"id":"start","type":"TRIGGER","label":"Start","config":{}},
          {"id":"open","type":"DEVICE_ACTION","label":"Open","config":{"preset":"web",
            "action":"browser.open","version":2,"target":"sameBrowser",
            "inputs":{"url":"https://example.com/b"}}},
          {"id":"read","type":"DEVICE_ACTION","label":"Read","config":{"preset":"web",
            "action":"browser.getText","version":1,"target":"sameBrowser",
            "inputs":{"element":{"strategies":[{"by":"role","role":"button","name":"Gone"}],
              "fingerprint":{"tag":"button"}}}}},
          {"id":"end","type":"END","label":"Done","config":{}}],
         "edges":[{"from":"start","to":"open"},{"from":"open","to":"read"},{"from":"read","to":"end"}]}
        """);
    String workflowId = api.post("/api/workflows", Map.of("name", "Dismiss me", "graph", graph))
        .json().path("id").asText();
    runToEnd(workflowId, 1);
    String fixId = api.get("/api/web/fixes?status=open").json().get(0).path("id").asText();

    assertThat(api.post("/api/web/fixes/" + fixId + "/dismiss", Map.of()).status()).isEqualTo(204);

    assertThat(api.get("/api/web/fixes?status=open").json()).isEmpty();
    assertThat(api.post("/api/web/fixes/" + fixId + "/apply", Map.of("candidateIndex", 0))
        .status()).isEqualTo(409);
    assertThat(api.get("/api/workflows/" + workflowId).json().path("version").asInt()).isEqualTo(1);
  }

  /** Runs a version by hand and waits until it has finished, failed or stopped for the person. */
  private JsonNode runToEnd(String workflowId, int version) throws Exception {
    var run = api.post("/api/workflows/" + workflowId + "/run", Map.of("version", version));
    assertThat(run.ok()).as(run.body()).isTrue();
    JsonNode current = run.json();
    for (int i = 0; i < 80 && java.util.Set.of("RUNNING", "WAITING")
        .contains(current.path("status").asText()); i++) {
      drainDeviceEvents();
      Thread.sleep(250);
      current = api.get("/api/runs/" + run.json().path("id").asText()).json();
    }
    return current;
  }

  private ApiClient signedIn(String prefix) {
    ApiClient client = new ApiClient(port);
    client.prime();
    String email = prefix + "-" + UUID.randomUUID() + "@jizza.local";
    client.post("/api/auth/register", Map.of("email", email, "password", PASSWORD));
    client.post("/api/auth/login", Map.of("email", email, "password", PASSWORD));
    client.post("/api/auth/step-up", Map.of("password", PASSWORD));
    return client;
  }
}
