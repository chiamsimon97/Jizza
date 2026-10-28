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
      assertThat(run.json().path("status").asText()).as(run.body()).isEqualTo("DONE");
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
  void anotherAccountCannotTestOnThisBrowser() {
    ApiClient other = signedIn("web-other");
    var tested = other.post("/api/web/test-step", Map.of(
        "deviceId", device.deviceId(),
        "action", "browser.open",
        "version", 2,
        "inputs", Map.of("url", "https://example.com")));
    assertThat(tested.status()).isEqualTo(404);
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
