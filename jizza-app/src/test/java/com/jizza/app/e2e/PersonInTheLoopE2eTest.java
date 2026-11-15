package com.jizza.app.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.devicesim.SimDevice;
import com.jizza.engine.api.enums.NodeType;
import com.jizza.engine.api.enums.WorkflowStatus;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.workflow.graph.WorkflowEdge;
import com.jizza.engine.api.workflow.graph.WorkflowGraph;
import com.jizza.engine.api.workflow.graph.WorkflowNode;
import com.jizza.engine.persistence.repository.WorkflowRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Web automation round 2 (plans 05 and 06): a browser asks the person mid-step, the card answers
 * it with a signed {@code resume} that the server never keeps, the command gets more time with a
 * signed {@code extend}, Stop cancels it, and a browser that signs its messages can't be spoken
 * for with its token alone.
 */
@org.junit.jupiter.api.condition.EnabledIf(
    value = "com.jizza.engine.persistence.support.TestDatabase#available",
    disabledReason = "no PostgreSQL: set JIZZA_TEST_DB_URL or start Docker")
class PersonInTheLoopE2eTest extends E2eSupport {

  private static final String PASSWORD = "Correct-Horse-92";
  private static final String CODE = "481516";

  @LocalServerPort private int port;
  @Autowired private WorkflowRepository workflows;
  @Autowired private JdbcTemplate jdbc;

  private ApiClient api;
  private SimDevice device;
  private final AtomicReference<String> typed = new AtomicReference<>();
  private final AtomicReference<JsonNode> resumed = new AtomicReference<>();

  @BeforeEach
  void setUp() {
    api = signedIn("person");
    device = new SimDevice("http://localhost:" + port, true);
    device.origin("chrome-extension://abcdefghijklmnopabcdefghijklmnop").keyStorage("browser");
    // What the extension does for an Ask step: say it needs the person, wait for the card's
    // answer, type it into the page.
    device.handle("browser.ask", input -> {
      String commandId = latestCommand("browser.ask");
      ObjectNode ask = Json.obj().put("commandId", commandId).put("kind", "ask")
          .put("message", input.path("message").asText())
          .put("deadline", Instant.now().plusSeconds(60).toString());
      ask.set("answer", Json.obj().put("secret", true).put("required", true));
      device.send("needs_user", ask);
      JsonNode resume = device.awaitMessage(m -> "resume".equals(m.path("type").asText())
          && commandId.equals(m.path("body").path("commandId").asText()), Duration.ofSeconds(30));
      if (resume == null) throw new SimDevice.Failure("timed_out", "nobody answered");
      resumed.set(resume);
      typed.set(resume.path("body").path("answer").asText(null));
      return Json.obj().put("answered", true).put("from", "card");
    });
    device.capability("browser", null);
    device.signMessages(true);
    device.enrollWithCode(pairingCode(), "Chromium browser", "browser");
    device.signIn();
    device.connect();
  }

  @AfterEach
  void tearDown() {
    device.close();
  }

  @Test
  void theCardsAnswerReachesTheBrowserSignedAndIsKeptNowhere() throws Exception {
    String runId = start("Type the code sent to your phone");
    JsonNode card = awaitHelpCard(runId);
    JsonNode help = card.path("context").path("deviceHelp");
    assertThat(help.path("kind").asText()).isEqualTo("ask");
    assertThat(help.path("answer").path("secret").asBoolean()).isTrue();
    assertThat(card.path("expiresAt").asText()).isNotBlank();
    // The browser was given more time while it waits for the person, signed by the server.
    JsonNode extend = device.awaitMessage(m -> "extend".equals(m.path("type").asText()),
        Duration.ofSeconds(5));
    assertThat(extend).isNotNull();
    assertThat(device.signedByServer(extend)).isTrue();

    // Answering needs the answer (or Done).
    var empty = api.post("/api/notifications/" + card.path("id").asText() + "/answer",
        Map.of("kind", "APPROVE", "inputs", Map.of()));
    assertThat(empty.status()).as(empty.body()).isEqualTo(400);
    var answered = api.post("/api/notifications/" + card.path("id").asText() + "/answer",
        Map.of("kind", "APPROVE", "inputs", Map.of("answer", CODE)));
    assertThat(answered.ok()).as(answered.body()).isTrue();

    JsonNode run = awaitRun(runId, Duration.ofSeconds(30));
    assertThat(run.path("status").asText()).as(run.toString()).isEqualTo("DONE");
    assertThat(typed.get()).isEqualTo(CODE);
    assertThat(device.signedByServer(resumed.get())).isTrue();

    // The code is in no record: not the card, the run, the audit or the inbox.
    assertThat(api.get("/api/notifications").body()).doesNotContain(CODE);
    assertThat(api.get("/api/runs/" + runId).body()).doesNotContain(CODE);
    Integer stored = jdbc.queryForObject("""
        SELECT (SELECT count(*) FROM notifications WHERE context::text LIKE ?)
             + (SELECT count(*) FROM audit_log WHERE detail::text LIKE ?)
             + (SELECT count(*) FROM workflow_runs WHERE context::text LIKE ?)
             + (SELECT count(*) FROM workflow_run_steps WHERE output::text LIKE ? OR input::text LIKE ?)
        """, Integer.class, "%" + CODE + "%", "%" + CODE + "%", "%" + CODE + "%",
        "%" + CODE + "%", "%" + CODE + "%");
    assertThat(stored).isZero();
  }

  @Test
  void stopOnTheCardStopsTheStep() throws Exception {
    String runId = start("Pay in the browser");
    JsonNode card = awaitHelpCard(runId);
    var stopped = api.post("/api/notifications/" + card.path("id").asText() + "/answer",
        Map.of("kind", "DENY", "feedback", "Stopped"));
    assertThat(stopped.ok()).as(stopped.body()).isTrue();
    JsonNode cancel = device.awaitMessage(m -> "cancel".equals(m.path("type").asText()),
        Duration.ofSeconds(10));
    assertThat(cancel).isNotNull();
    assertThat(cancel.path("body").path("reason").asText()).isEqualTo("Stopped by you");
  }

  @Test
  void aBrowserThatSignsCannotBeSpokenForWithItsTokenAlone() {
    // Its messages so far were signed, so the server now expects it.
    device.signMessages(false);
    JsonNode unsigned = device.send("event", Json.obj().put("name", "browser.recording.step")
        .set("data", Json.obj()));
    assertThat(unsigned.path("type").asText()).isEqualTo("error");
    assertThat(unsigned.path("body").path("code").asText()).isEqualTo("unauthorized");

    ObjectNode forged = device.envelope("event", Json.obj().put("name", "x").set("data", Json.obj()));
    forged.put("sig", device.signEnvelope(forged));
    ((ObjectNode) forged.get("body")).put("name", "forged");
    JsonNode tampered = device.sendEnvelope(forged);
    assertThat(tampered.path("body").path("code").asText()).isEqualTo("bad_signature");

    // A signed message sent twice is acted on once and acknowledged both times.
    ObjectNode once = device.envelope("event", Json.obj().put("name", "ping.again")
        .set("data", Json.obj()));
    once.put("sig", device.signEnvelope(once));
    assertThat(device.sendEnvelope(once.deepCopy()).path("type").asText()).isEqualTo("ack");
    assertThat(device.sendEnvelope(once.deepCopy()).path("type").asText()).isEqualTo("ack");
  }

  // ── Helpers ────────────────────────────────────────────────────────────────

  private String latestCommand(String action) {
    List<JsonNode> received = device.received();
    for (int i = received.size() - 1; i >= 0; i--) {
      JsonNode m = received.get(i);
      if ("command".equals(m.path("type").asText())
          && action.equals(m.path("body").path("action").asText())) {
        return m.path("body").path("commandId").asText();
      }
    }
    throw new IllegalStateException("no " + action + " command");
  }

  private String start(String message) {
    UUID workflowId = approvedWorkflow(Json.obj().put("action", "browser.ask").put("version", 1)
        .put("target", device.deviceId().toString())
        .set("inputs", Json.obj().put("message", message)));
    var run = api.post("/api/workflows/" + workflowId + "/run", Map.of("version", 1));
    assertThat(run.ok()).as(run.body()).isTrue();
    return run.json().path("id").asText();
  }

  private JsonNode awaitHelpCard(String runId) throws InterruptedException {
    long until = System.nanoTime() + Duration.ofSeconds(20).toNanos();
    while (System.nanoTime() < until) {
      for (JsonNode n : api.get("/api/notifications").json()) {
        if (runId.equals(n.path("runId").asText()) && n.path("context").has("deviceHelp")
            && "UNREAD".equals(n.path("status").asText())) {
          return n;
        }
      }
      Thread.sleep(100);
    }
    throw new AssertionError("No card for the browser's question: "
        + api.get("/api/notifications").body());
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

  private String pairingCode() {
    var pairing = api.post("/api/devices/pairings", Map.of());
    assertThat(pairing.ok()).as(pairing.body()).isTrue();
    return pairing.json().path("code").asText();
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
            new WorkflowNode("ask", NodeType.DEVICE_ACTION, "ask", config,
                new WorkflowNode.RetryPolicy(1, 1, 1.0, 50)),
            new WorkflowNode("finish", NodeType.END, "finish", null, null)),
        List.of(new WorkflowEdge("start", "ask", null), new WorkflowEdge("ask", "finish", null)));
    var saved = api.post("/api/workflows", Map.of("name", "Ask me", "graph", graph.toJson()));
    assertThat(saved.ok()).as(saved.body()).isTrue();
    UUID id = UUID.fromString(saved.json().path("id").asText());
    var entity = workflows.findById(id).orElseThrow();
    entity.setStatus(WorkflowStatus.APPROVED);
    workflows.save(entity);
    return id;
  }
}
