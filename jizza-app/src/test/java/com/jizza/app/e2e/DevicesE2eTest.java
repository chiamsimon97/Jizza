package com.jizza.app.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.devicesim.FakeBrowser;
import com.jizza.devicesim.SimDevice;
import com.jizza.engine.api.enums.NodeType;
import com.jizza.engine.api.enums.WorkflowStatus;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.workflow.graph.WorkflowEdge;
import com.jizza.engine.api.workflow.graph.WorkflowGraph;
import com.jizza.engine.api.workflow.graph.WorkflowNode;
import com.jizza.engine.persistence.repository.WorkflowRepository;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Conformance for agent protocol v1, driven with the device simulator against the whole server:
 * pairing, signing in, manifests, commands over the WebSocket and over HTTPS polling, the Device
 * action workflow step, and what a device of another person, a revoked device or a stale token
 * cannot do. A real app passes when it behaves as {@link SimDevice} does in each of these.
 */
@org.junit.jupiter.api.condition.EnabledIf(
    value = "com.jizza.engine.persistence.support.TestDatabase#available",
    disabledReason = "no PostgreSQL: set JIZZA_TEST_DB_URL or start Docker")
class DevicesE2eTest extends E2eSupport {

  private static final String PASSWORD = "Correct-Horse-92";

  @LocalServerPort private int port;
  @Autowired private WorkflowRepository workflows;

  private ApiClient api;
  private SimDevice device;

  @BeforeEach
  void setUp() {
    api = signedIn("devices");
    device = new SimDevice("http://localhost:" + port);
    device.handle("system.info", input -> device.json().createObjectNode()
        .put("os", "Simulator").put("hostname", "sim-host"));
    device.handle("notify.show", input -> device.json().createObjectNode()
        .put("shown", true).put("said", input.path("message").asText()));
    device.capability("system", null).capability("notify", null);
  }

  @AfterEach
  void tearDown() {
    device.close();
  }

  @Test
  void aPairedDeviceSignsInAndDeclaresWhatItCanDo() {
    String code = pairingCode();
    device.enrollWithCode(code, "Work laptop", "windows");
    device.signIn();
    JsonNode welcome = device.hello();
    assertThat(welcome.path("type").asText()).as(welcome.toString()).isEqualTo("welcome");
    assertThat(welcome.path("body").path("protocol").asInt()).isEqualTo(1);
    assertThat(device.sendManifest().path("type").asText()).isEqualTo("ack");

    var listed = api.get("/api/devices");
    assertThat(listed.ok()).as(listed.body()).isTrue();
    JsonNode laptop = listed.json().get(0);
    assertThat(laptop.path("name").asText()).isEqualTo("Work laptop");
    assertThat(laptop.path("platform").asText()).isEqualTo("WINDOWS");
    assertThat(laptop.path("actions").toString()).contains("system.info", "notify.show");
    // Codes work once.
    assertThatThrownBy(() -> new SimDevice("http://localhost:" + port)
        .enrollWithCode(code, "Again", "linux"))
        .isInstanceOf(SimDevice.SimException.class);
    // The person is told a device was added.
    assertThat(api.get("/api/notifications").body()).contains("New device paired: Work laptop");
  }

  @Test
  void aBrowserPairsWithItsPlatformKeyStorageAndWebManifest() {
    new FakeBrowser(device);
    device.keyStorage("browser");
    device.enrollWithCode(pairingCode(), "Chrome on MacBook", "browser");
    device.signIn();
    assertThat(device.hello().path("type").asText()).isEqualTo("welcome");
    assertThat(device.sendManifest().path("type").asText()).isEqualTo("ack");

    JsonNode browser = api.get("/api/devices").json().get(0);
    assertThat(browser.path("platform").asText()).isEqualTo("BROWSER");
    assertThat(browser.path("keyStorage").asText()).isEqualTo("browser");
    assertThat(browser.path("actions").toString())
        .contains("browser.open@2", "browser.click@2", "browser.record@1");
    assertThat(browser.path("capabilities").path("browser").path("family").asText())
        .isEqualTo("chrome");
  }

  @Test
  void aPhoneWithAP256HardwareKeyPairsAndChecksTheServerKeyFingerprint() {
    var pairing = api.post("/api/devices/pairings", Map.of());
    String qr = pairing.json().path("qrPayload").asText();
    SimDevice phone = new SimDevice("http://localhost:" + port, true);
    phone.enrollWithCode(pairing.json().path("code").asText(), "Chiam's iPhone", "ios");
    // The phone trusts the key it was given only when it matches the QR code it scanned.
    assertThat(qr).contains("&fp=" + phone.serverKeyFingerprint());
    phone.signIn();
    assertThat(phone.hello().path("type").asText()).isEqualTo("welcome");
    var shown = api.get("/api/devices/" + phone.deviceId());
    assertThat(shown.json().path("keyStorage").asText()).isEqualTo("secure_enclave");
    assertThat(shown.json().path("platform").asText()).isEqualTo("IOS");
  }

  @Test
  void testConnectionRoundTripsOverHttpsPolling() throws Exception {
    pairAndSignIn();
    AtomicBoolean polling = new AtomicBoolean(true);
    Thread poller = Thread.ofVirtual().start(() -> {
      while (polling.get()) device.pollOnce(2);
    });
    try {
      var tested = api.post("/api/devices/" + device.deviceId() + "/test", Map.of());
      assertThat(tested.ok()).as(tested.body()).isTrue();
      assertThat(tested.json().path("status").asText()).as(tested.body()).isEqualTo("SUCCEEDED");
      assertThat(tested.json().path("output").path("pong").asBoolean()).isTrue();
    } finally {
      polling.set(false);
      poller.join(5000);
    }
    var commands = api.get("/api/devices/" + device.deviceId() + "/commands");
    assertThat(commands.json().get(0).path("action").asText()).isEqualTo("system.ping");
  }

  @Test
  void aWorkflowStepRunsOnTheDeviceOverTheWebSocket() throws Exception {
    pairAndSignIn();
    device.connect();
    UUID workflowId = approvedWorkflow(Json.obj().put("action", "notify.show")
        .put("target", device.deviceId().toString())
        .set("inputs", Json.obj().put("message", "Report is ready")));

    var run = api.post("/api/workflows/" + workflowId + "/run", Map.of("version", 1));
    assertThat(run.ok()).as(run.body()).isTrue();
    String runId = run.json().path("id").asText();
    JsonNode finished = awaitRun(runId, Duration.ofSeconds(20));
    assertThat(finished.path("status").asText()).as(finished.toString()).isEqualTo("DONE");
    assertThat(finished.toString()).contains("Report is ready");
    // The command the device ran was signed by the server and checked by the device.
    JsonNode command = device.received().stream()
        .filter(m -> "command".equals(m.path("type").asText())).findFirst().orElseThrow();
    assertThat(command.path("sig").asText()).isNotBlank();
    assertThat(command.path("body").path("input").path("message").asText()).isEqualTo("Report is ready");
  }

  @Test
  void aToolTheDeviceAddedRunsInAWorkflowWithItsOwnInputsChecked() throws Exception {
    ObjectNode input = Json.obj().put("type", "object");
    input.putArray("required").add("ledger");
    input.putObject("properties").putObject("ledger").put("type", "string");
    ObjectNode tool = Json.obj().put("action", "x-acme.exportLedger").put("version", 2)
        .put("title", "Export the ledger").put("timeoutSec", 60);
    tool.set("input", input);
    tool.set("output", Json.obj().put("type", "object").set("properties",
        Json.obj().set("rows", Json.obj().put("type", "integer"))));
    device.tool(tool, in -> device.json().createObjectNode()
        .put("rows", 42).put("ledger", in.path("ledger").asText()));
    device.items("shortcuts", device.json().createArrayNode()
        .add(device.json().createObjectNode().put("id", "Log water").put("name", "Log water")));
    pairAndSignIn();
    device.connect();

    // The builder sees the tool with its schemas, and the saved items, on the device.
    var shown = api.get("/api/devices/" + device.deviceId());
    assertThat(shown.json().path("actions").toString()).contains("x-acme.exportLedger@2");
    JsonNode described = shown.json().path("tools").get(0);
    assertThat(described.path("title").asText()).isEqualTo("Export the ledger");
    assertThat(described.path("input").path("required").get(0).asText()).isEqualTo("ledger");
    assertThat(described.path("actionType").asText()).isEqualTo("MONEY");
    assertThat(shown.json().path("items").path("shortcuts").get(0).path("name").asText())
        .isEqualTo("Log water");
    // The catalog says which input takes a saved Shortcut.
    assertThat(api.get("/api/devices/catalog").body()).contains("\"x-items\":\"shortcuts\"");

    UUID workflowId = approvedWorkflow(Json.obj().put("action", "x-acme.exportLedger")
        .put("target", device.deviceId().toString())
        .set("inputs", Json.obj().put("ledger", "2026-Q3")));
    // Until the owner says what the tool does, it counts as moving money, which never runs alone.
    var held = api.post("/api/workflows/" + workflowId + "/run", Map.of("version", 1));
    assertThat(held.json().path("status").asText()).as(held.body())
        .isEqualTo("PAUSED_WAITING_INSTRUCTION");
    assertThat(api.get("/api/runs/" + held.json().path("id").asText() + "/trace").body())
        .contains("guardrails");
    var typed = api.patch("/api/devices/" + device.deviceId() + "/tools/x-acme.exportLedger",
        Map.of("actionType", "READ"));
    assertThat(typed.json().path("tools").get(0).path("actionType").asText()).as(typed.body())
        .isEqualTo("READ");

    var run = api.post("/api/workflows/" + workflowId + "/run", Map.of("version", 1));
    assertThat(run.ok()).as(run.body()).isTrue();
    JsonNode finished = awaitRun(run.json().path("id").asText(), Duration.ofSeconds(20));
    assertThat(finished.path("status").asText())
        .as(api.get("/api/runs/" + run.json().path("id").asText() + "/trace").body()).isEqualTo("DONE");
    assertThat(finished.toString()).contains("2026-Q3");

    // Inputs that do not fit the device's own schema are refused before anything is sent.
    UUID wrong = approvedWorkflow(Json.obj().put("action", "x-acme.exportLedger")
        .put("target", device.deviceId().toString())
        .set("inputs", Json.obj().put("year", 2026)));
    var refused = api.post("/api/workflows/" + wrong + "/run", Map.of("version", 1));
    assertThat(refused.json().path("status").asText()).as(refused.body())
        .isEqualTo("PAUSED_WAITING_INSTRUCTION");
    assertThat(api.get("/api/runs/" + refused.json().path("id").asText() + "/trace").body())
        .contains("ledger");
  }

  @Test
  void aManifestWithABadToolIsRefused() {
    device.tool(Json.obj().put("action", "acme.notPrefixed"), in -> in);
    device.enrollWithCode(pairingCode(), "Sim laptop", "windows");
    device.signIn();
    device.hello();
    JsonNode answer = device.sendManifest();
    assertThat(answer.path("type").asText()).as(answer.toString()).isEqualTo("error");
    assertThat(answer.toString()).contains("x-acme.export");
  }

  @Test
  void aStepForAnActionTheDeviceDidNotOfferFailsClearly() {
    pairAndSignIn();
    UUID workflowId = approvedWorkflow(Json.obj().put("action", "browser.open")
        .put("target", device.deviceId().toString())
        .set("inputs", Json.obj().put("url", "https://example.com")));
    var run = api.post("/api/workflows/" + workflowId + "/run", Map.of("version", 1));
    // As any step that cannot go on, it stops and asks the person what to do, saying why.
    assertThat(run.json().path("status").asText()).as(run.body())
        .isEqualTo("PAUSED_WAITING_INSTRUCTION");
    var trace = api.get("/api/runs/" + run.json().path("id").asText() + "/trace");
    assertThat(trace.body()).contains("did not offer that action");
  }

  @Test
  void aTamperedCommandIsRefusedByTheDevice() {
    pairAndSignIn();
    var sent = api.post("/api/devices/" + device.deviceId() + "/test", Map.of());
    assertThat(sent.json().path("status").asText()).as(sent.body()).isEqualTo("QUEUED");
    device.autoRun(false);
    JsonNode command = device.pollOnce(1).stream()
        .filter(m -> "command".equals(m.path("type").asText())).findFirst().orElseThrow();
    ObjectNode forged = command.deepCopy();
    ((ObjectNode) forged.get("body")).put("action", "shell.run");
    device.run(forged);
    var commands = api.get("/api/devices/" + device.deviceId() + "/commands");
    assertThat(commands.json().get(0).path("status").asText()).isEqualTo("REJECTED");
    assertThat(commands.json().get(0).path("errorCode").asText()).isEqualTo("bad_signature");
  }

  @Test
  void enrollmentTokensAddMachinesWithTheirTags() {
    var token = api.post("/api/devices/enrollment-tokens",
        Map.of("name", "Build servers", "uses", 2, "validDays", 1, "tags", List.of("ci")));
    assertThat(token.ok()).as(token.body()).isTrue();
    device.enrollWithToken(token.json().path("token").asText(), "ci-runner-1", "linux");
    var shown = api.get("/api/devices/" + device.deviceId());
    assertThat(shown.json().path("tags").toString()).contains("ci");
    // The token itself is never shown again.
    assertThat(api.get("/api/devices/enrollment-tokens").body())
        .doesNotContain(token.json().path("token").asText());
  }

  @Test
  void aRevokedDeviceCanNoLongerSignInOrSpeak() {
    pairAndSignIn();
    String oldToken = device.token();
    assertThat(api.delete("/api/devices/" + device.deviceId()).ok()).isTrue();
    assertThatThrownBy(device::signIn).isInstanceOf(SimDevice.SimException.class)
        .hasMessageContaining("401");
    assertThat(oldToken).isNotBlank();
    assertThatThrownBy(() -> device.pollOnce(0)).isInstanceOf(SimDevice.SimException.class)
        .hasMessageContaining("device_revoked");
    assertThat(api.get("/api/devices/" + device.deviceId()).status()).isEqualTo(404);
  }

  @Test
  void anotherPersonCannotSeeOrUseTheDevice() {
    pairAndSignIn();
    ApiClient other = signedIn("devices-other");
    assertThat(other.get("/api/devices/" + device.deviceId()).status()).isEqualTo(404);
    assertThat(other.get("/api/devices").json().findValuesAsText("id"))
        .doesNotContain(device.deviceId().toString());
    assertThat(other.post("/api/devices/" + device.deviceId() + "/test", Map.of()).status())
        .isEqualTo(404);
  }

  @Test
  void agentEndpointsNeedADeviceToken() {
    var anonymous = new ApiClient(port);
    assertThat(anonymous.get("/agent/v1/commands").status()).isEqualTo(401);
    assertThat(anonymous.get("/agent/v1/catalog").status()).isEqualTo(401);
    // A browser session is not a device token.
    assertThat(api.get("/agent/v1/catalog").status()).isEqualTo(401);
  }

  @Test
  void aMessageTypeTheProtocolDoesNotHaveGetsAnError() {
    pairAndSignIn();
    JsonNode reply = device.send("teleport", Json.obj());
    assertThat(reply.path("type").asText()).isEqualTo("error");
    assertThat(reply.path("body").path("code").asText()).isEqualTo("unsupported_type");
  }

  // ── Helpers ────────────────────────────────────────────────────────────────

  private ApiClient signedIn(String prefix) {
    ApiClient client = new ApiClient(port);
    client.prime();
    String email = prefix + "-" + UUID.randomUUID() + "@jizza.local";
    client.post("/api/auth/register", Map.of("email", email, "password", PASSWORD));
    client.post("/api/auth/login", Map.of("email", email, "password", PASSWORD));
    client.post("/api/auth/step-up", Map.of("password", PASSWORD));
    return client;
  }

  private String pairingCode() {
    var pairing = api.post("/api/devices/pairings", Map.of());
    assertThat(pairing.ok()).as(pairing.body()).isTrue();
    assertThat(pairing.json().path("qrPayload").asText()).startsWith("jizza://pair?code=");
    return pairing.json().path("code").asText();
  }

  private void pairAndSignIn() {
    device.enrollWithCode(pairingCode(), "Sim laptop", "windows");
    device.signIn();
    device.hello();
    device.sendManifest();
  }

  private JsonNode awaitRun(String runId, Duration wait) throws InterruptedException {
    long until = System.nanoTime() + wait.toNanos();
    JsonNode run = null;
    while (System.nanoTime() < until) {
      run = api.get("/api/runs/" + runId).json();
      String status = run.path("status").asText();
      if (status.equals("DONE") || status.equals("FAILED") || status.equals("CANCELLED")) return run;
      Thread.sleep(100);
    }
    return run;
  }

  private UUID approvedWorkflow(JsonNode config) {
    WorkflowGraph graph = new WorkflowGraph(
        List.of(new WorkflowNode("start", NodeType.TRIGGER, "start", null, null),
            new WorkflowNode("device", NodeType.DEVICE_ACTION, "device", config,
                new WorkflowNode.RetryPolicy(1, 1, 1.0, 50)),
            new WorkflowNode("finish", NodeType.END, "finish", null, null)),
        List.of(new WorkflowEdge("start", "device", null),
            new WorkflowEdge("device", "finish", null)));
    var saved = api.post("/api/workflows", Map.of("name", "On my laptop", "graph", graph.toJson()));
    assertThat(saved.ok()).as(saved.body()).isTrue();
    UUID id = UUID.fromString(saved.json().path("id").asText());
    var entity = workflows.findById(id).orElseThrow();
    entity.setStatus(WorkflowStatus.APPROVED);
    workflows.save(entity);
    return id;
  }
}
