package com.jizza.app.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.dto.AgentDto;
import com.jizza.engine.api.enums.EscalationState;
import com.jizza.engine.api.enums.NodeType;
import com.jizza.engine.api.enums.NotificationKind;
import com.jizza.engine.api.enums.NotificationStatus;
import com.jizza.engine.api.enums.WorkflowStatus;
import com.jizza.engine.api.llm.LlmResponse;
import com.jizza.engine.api.notification.NotificationBus;
import com.jizza.engine.api.notification.NotificationContext;
import com.jizza.engine.api.orchestrator.AgentDirectory;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.persistence.repository.AuditLogRepository;
import com.jizza.engine.persistence.repository.KnowledgeEntryRepository;
import com.jizza.engine.persistence.repository.NotificationRepository;
import com.jizza.engine.persistence.repository.UserRepository;
import com.jizza.engine.persistence.repository.WorkflowRepository;
import com.jizza.engine.persistence.repository.WorkflowRunRepository;
import com.jizza.engine.api.workflow.graph.WorkflowEdge;
import com.jizza.engine.api.workflow.graph.WorkflowGraph;
import com.jizza.engine.api.workflow.graph.WorkflowNode;
import com.jizza.engine.notifications.config.NotificationProperties;
import com.jizza.engine.notifications.escalation.EscalationSweeper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

/**
 * Acceptance for the platform layer: blocking items are chased and expire, realtime pushes reach a
 * subscribed client, the service reports its own health, a verified webhook starts work, and the
 * user can take their data out or have all of it destroyed -- with the audit chain standing behind
 * the record of it (build prompt Acceptance Criteria 14, 15 and 16).
 */
@org.junit.jupiter.api.condition.EnabledIf(
    value = "com.jizza.engine.persistence.support.TestDatabase#available",
    disabledReason = "no PostgreSQL: set JIZZA_TEST_DB_URL or start Docker")
class PlatformE2eTest extends E2eSupport {

  private static final String PASSWORD = "Correct-Horse-92";
  private static final String WEBHOOK_SECRET = "shhh-webhook-secret";

  @LocalServerPort private int port;
  @Autowired private AgentDirectory agents;
  @Autowired private NotificationBus bus;
  @Autowired private NotificationRepository notifications;
  @Autowired private NotificationProperties notificationProps;
  @Autowired private EscalationSweeper sweeper;
  @Autowired private AuditLogRepository auditEntries;
  @Autowired private UserRepository users;
  @Autowired private KnowledgeEntryRepository knowledge;
  @Autowired private WorkflowRepository workflows;
  @Autowired private WorkflowRunRepository runs;
  @Autowired private E2eSupport.ScriptedLlmClient model;

  private ApiClient api;
  private UUID userId;
  private String email;

  @BeforeEach
  void setUp() {
    api = new ApiClient(port);
    api.prime();
    model.reset();

    email = "plat-" + UUID.randomUUID() + "@jizza.local";
    api.post("/api/auth/register", Map.of("email", email, "password", PASSWORD));
    userId = UUID.fromString(api.post("/api/auth/login",
        Map.of("email", email, "password", PASSWORD)).json().path("userId").asText());
    api.post("/api/auth/step-up", Map.of("password", PASSWORD));
  }

  private UUID agentId(String key) {
    return agents.byKey(userId, key).map(AgentDto::id).orElseThrow();
  }

  /** Raises a real blocking item through the bus, the way an agent would. */
  private UUID blockingItem(String body) {
    return bus.raise(NotificationKind.PERMISSION_REQUEST, agentId("finance"),
        new NotificationContext(userId, body, Json.obj().put("tool", "pay_supplier"),
            null, null, null, null)).id();
  }

  /** Moves an item's expiry, which is how the tests reach a state the clock would take days to. */
  private void expiresIn(UUID id, Duration remaining) {
    var entity = notifications.findById(id).orElseThrow();
    entity.setExpiresAt(Instant.now().plus(remaining));
    notifications.save(entity);
  }

  // ── blocking items: chased, then expired ─────────────────────────────────

  @Test
  void aBlockingItemCarriesAnExpiryFromTheStart() {
    UUID id = blockingItem("Approve a payment to Acme?");

    var entity = notifications.findById(id).orElseThrow();
    assertThat(entity.getExpiresAt())
        .as("nothing that blocks work is allowed to hang indefinitely")
        .isCloseTo(Instant.now().plus(notificationProps.getPermissionExpiry()),
            org.assertj.core.api.Assertions.within(1, java.time.temporal.ChronoUnit.MINUTES));
    assertThat(entity.getEscalationState()).isEqualTo(EscalationState.NONE);
  }

  @Test
  void anItemCloseToExpiringIsChasedAndTheChaseIsAudited() {
    UUID id = blockingItem("Approve a payment to Acme?");
    expiresIn(id, Duration.ofMinutes(30));  // inside the reminder horizon

    sweeper.sweep();

    var entity = notifications.findById(id).orElseThrow();
    assertThat(entity.getReminderCount()).isEqualTo(1);
    assertThat(entity.getEscalationState())
        .as("the chase goes out of the app, not just into the notification list")
        .isEqualTo(EscalationState.ESCALATED);
    assertThat(entity.getStatus()).as("still answerable").isEqualTo(NotificationStatus.UNREAD);
    assertThat(api.get("/api/activity?action=notification.reminded").json()).isNotEmpty();
  }

  @Test
  void theSameItemIsNotChasedAgainWithinItsCooldown() {
    UUID id = blockingItem("Approve a payment to Acme?");
    expiresIn(id, Duration.ofMinutes(30));

    sweeper.sweep();
    sweeper.sweep();

    assertThat(notifications.findById(id).orElseThrow().getReminderCount())
        .as("a reminder is a nudge, not a stream").isEqualTo(1);
  }

  @Test
  void anItemNobodyAnswersExpiresSoTheWorkBehindItGetsADefiniteOutcome() {
    UUID id = blockingItem("Approve a payment to Acme?");
    expiresIn(id, Duration.ofSeconds(-1));

    sweeper.sweep();

    var entity = notifications.findById(id).orElseThrow();
    assertThat(entity.getStatus()).isEqualTo(NotificationStatus.EXPIRED);
    assertThat(entity.getEscalationState()).isEqualTo(EscalationState.AUTO_CANCELLED);
    assertThat(api.get("/api/activity?action=notification.expired").json()).isNotEmpty();

    // And an expired request cannot then be answered into an approval.
    var late = api.post("/api/notifications/" + id + "/answer", Map.of("kind", "APPROVE"));
    assertThat(late.ok()).isFalse();
  }

  @Test
  void anAnsweredItemIsLeftAlone() {
    UUID id = blockingItem("Approve a payment to Acme?");
    assertThat(api.post("/api/notifications/" + id + "/answer", Map.of("kind", "APPROVE")).ok())
        .isTrue();
    expiresIn(id, Duration.ofSeconds(-1));

    sweeper.sweep();

    assertThat(notifications.findById(id).orElseThrow().getStatus())
        .as("answered is a terminal state; the sweeper must not overwrite it")
        .isEqualTo(NotificationStatus.APPROVED);
  }

  // ── realtime ─────────────────────────────────────────────────────────────

  @Test
  void aSubscribedClientIsPushedTheNotificationTheMomentItIsRaised() throws Exception {
    var client = new WebSocketStompClient(new StandardWebSocketClient());
    client.setMessageConverter(new org.springframework.messaging.converter.SimpleMessageConverter());
    StompSession session = connect(client, api);
    BlockingQueue<String> received = subscribe(session, "/user/queue/notifications");

    blockingItem("Approve a payment to Acme?");

    String frame = received.poll(10, TimeUnit.SECONDS);
    assertThat(frame).as("the push arrived without the client asking").isNotNull();
    assertThat(frame).contains("Approve a payment to Acme?").contains("PERMISSION_REQUEST");
    session.disconnect();
    client.stop();
  }

  @Test
  void anotherUsersSocketIsNeverPushedThisUsersNotifications() throws Exception {
    ApiClient stranger = new ApiClient(port);
    stranger.prime();
    String email = "stranger-" + UUID.randomUUID() + "@jizza.local";
    stranger.post("/api/auth/register", Map.of("email", email, "password", "Correct-Horse-92"));
    stranger.post("/api/auth/login", Map.of("email", email, "password", "Correct-Horse-92"));

    var client = new WebSocketStompClient(new StandardWebSocketClient());
    client.setMessageConverter(new org.springframework.messaging.converter.SimpleMessageConverter());
    StompSession theirs = connect(client, stranger);
    BlockingQueue<String> ownQueue = subscribe(theirs, "/user/queue/notifications");
    // The old per-user topic was guessable; nothing is published there any more.
    BlockingQueue<String> guessed = subscribe(theirs, "/topic/" + userId + "/notifications");
    StompSession mine = connect(client, api);
    BlockingQueue<String> received = subscribe(mine, "/user/queue/notifications");

    blockingItem("Approve a payment to Acme?");

    assertThat(received.poll(10, TimeUnit.SECONDS)).isNotNull();
    assertThat(ownQueue.poll(1, TimeUnit.SECONDS)).isNull();
    assertThat(guessed.poll(1, TimeUnit.SECONDS)).isNull();
    theirs.disconnect();
    mine.disconnect();
    client.stop();
  }

  private StompSession connect(WebSocketStompClient client, ApiClient as) throws Exception {
    var handshake = new WebSocketHttpHeaders();
    handshake.add("Cookie", as.cookieHeader());
    // SockJS's raw WebSocket transport; the browser client negotiates its way to the same place.
    return client
        .connectAsync("ws://localhost:" + port + "/ws/websocket", handshake,
            new StompSessionHandlerAdapter() {})
        .get(10, TimeUnit.SECONDS);
  }

  private static BlockingQueue<String> subscribe(StompSession session, String destination)
      throws InterruptedException {
    BlockingQueue<String> received = new LinkedBlockingQueue<>();
    session.subscribe(destination, new StompFrameHandler() {
      @Override public java.lang.reflect.Type getPayloadType(StompHeaders headers) {
        return byte[].class;
      }
      @Override public void handleFrame(StompHeaders headers, Object payload) {
        received.add(new String((byte[]) payload, StandardCharsets.UTF_8));
      }
    });
    // A SUBSCRIBE is asynchronous; give the broker a moment to register it before publishing.
    Thread.sleep(200);
    return received;
  }

  // ── the service reporting on itself ──────────────────────────────────────

  @Test
  void healthAndMetricsAreExposedForOperations() {
    var health = api.get("/actuator/health");
    assertThat(health.ok()).as(health.body()).isTrue();
    assertThat(health.json().path("status").asText()).isEqualTo("UP");

    var metrics = api.get("/actuator/metrics");
    assertThat(metrics.ok()).as(metrics.body()).isTrue();
    assertThat(metrics.json().path("names").toString())
        .contains("jvm.memory.used").contains("http.server.requests");

    // Operational endpoints are not public: only the probes are.
    var anonymous = new ApiClient(port);
    assertThat(anonymous.get("/actuator/health").ok()).isTrue();
    assertThat(anonymous.get("/actuator/metrics").status()).isEqualTo(401);
  }

  @Test
  void aPathNobodyServesIsANotFoundRatherThanAnInternalError() {
    var missing = api.get("/api/there-is-no-such-thing");

    assertThat(missing.status()).isEqualTo(404);
    assertThat(missing.problemType()).endsWith("not-found");
  }

  @Test
  void everyResponseCarriesARequestIdThatTheAuditLogAlsoRecords() {
    api.post("/api/tasks", Map.of(
        "title", "Anything", "agentId", agentId("professional").toString(),
        "scheduleType", "ONE_TIME", "runAt", Instant.now().plusSeconds(3600).toString()));

    var entry = auditEntries.findByUserIdOrderBySeqAsc(userId).stream()
        .filter(e -> "task.scheduled".equals(e.getAction())).findFirst().orElseThrow();
    assertThat(entry.getRequestId())
        .as("a request id ties a log line and an audit entry to the same call").isNotBlank();
  }

  // ── the audit chain and the activity view ────────────────────────────────

  @Test
  void theActivityViewReadsTheAuditLogInTheNamesTheUserKnows() {
    model.scriptFor("Jizza", LlmResponse.text("Noted."));
    api.post("/api/chat", Map.of("message", "hello"));

    var activity = api.get("/api/activity?limit=50");

    assertThat(activity.ok()).as(activity.body()).isTrue();
    assertThat(activity.json()).isNotEmpty();
    assertThat(activity.json().findValuesAsText("actorName")).contains("You");
    assertThat(activity.json().findValuesAsText("action")).contains("auth.login.success");
    // Every entry is chained, and the view shows the hash so it can be checked independently.
    assertThat(activity.json().get(0).path("hash").asText()).isNotBlank();
  }

  @Test
  void theChainVerifiesAndAnAlteredEntryIsFoundExactlyWhereItWasChanged() {
    for (int i = 0; i < 3; i++) {
      api.post("/api/tasks", Map.of(
          "title", "Task " + i, "agentId", agentId("professional").toString(),
          "scheduleType", "ONE_TIME", "runAt", Instant.now().plusSeconds(3600).toString()));
    }

    var intact = api.get("/api/activity/verify");
    assertThat(intact.json().path("intact").asBoolean()).as(intact.body()).isTrue();
    assertThat(intact.json().path("entries").asLong()).isGreaterThan(3);

    // Rewrite history the way someone with database access would.
    var entries = auditEntries.findByUserIdOrderBySeqAsc(userId);
    var tampered = entries.get(entries.size() / 2);
    tampered.setAction("something.else");
    auditEntries.saveAndFlush(tampered);

    var broken = api.get("/api/activity/verify");
    assertThat(broken.json().path("intact").asBoolean()).isFalse();
    assertThat(UUID.fromString(broken.json().path("firstBrokenEntryId").asText()))
        .as("the chain names the first entry that no longer hashes to its recorded value")
        .isEqualTo(tampered.getId());
  }

  // ── inbound webhooks ─────────────────────────────────────────────────────

  private UUID approvedWorkflow(String name) {
    WorkflowGraph graph = new WorkflowGraph(
        List.of(new WorkflowNode("start", NodeType.TRIGGER, "start", null, null),
            new WorkflowNode("read", NodeType.TRANSFORM, "read",
                Json.obj().put("expression", "input.event.ticket"), null),
            new WorkflowNode("finish", NodeType.END, "finish", null, null)),
        List.of(new WorkflowEdge("start", "read", null),
            new WorkflowEdge("read", "finish", null)));

    var saved = api.post("/api/workflows", Map.of("name", name, "graph", graph.toJson()));
    assertThat(saved.ok()).as(saved.body()).isTrue();
    UUID id = UUID.fromString(saved.json().path("id").asText());
    var entity = workflows.findById(id).orElseThrow();
    entity.setStatus(WorkflowStatus.APPROVED);
    workflows.save(entity);
    return id;
  }

  private String subscribeWebhook(UUID workflowId) {
    var connection = api.post("/api/connections", Map.of(
        "serviceName", "Ticketing", "authScheme", "API_KEY", "clientSecret", "k",
        "baseUrl", "https://tickets.example.com"));
    assertThat(connection.ok()).as(connection.body()).isTrue();

    var endpoint = api.post("/api/webhook-endpoints", Map.of(
        "connectionId", connection.json().path("id").asText(),
        "workflowId", workflowId.toString(),
        "verifyConfig", Json.obj().put("type", "hmac_sha256").put("header", "X-Signature")
            .put("secret", WEBHOOK_SECRET).put("version", 1)));
    assertThat(endpoint.ok()).as(endpoint.body()).isTrue();
    String url = endpoint.json().path("url").asText();
    return url.substring(url.lastIndexOf('/') + 1);
  }

  private static String sign(String body) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(WEBHOOK_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      StringBuilder hex = new StringBuilder();
      for (byte b : mac.doFinal(body.getBytes(StandardCharsets.UTF_8))) {
        hex.append(String.format("%02x", b));
      }
      return hex.toString();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** The webhook route takes no session, so it is called with a bare client. */
  private ApiClient.Result deliver(String token, String body, String signature) {
    var caller = new ApiClient(port);
    var request = java.net.http.HttpRequest
        .newBuilder(java.net.URI.create("http://localhost:" + port + "/api/webhooks/" + token))
        .header("content-type", "application/json");
    if (signature != null) request.header("X-Signature", signature);
    request.POST(java.net.http.HttpRequest.BodyPublishers.ofString(body));
    try {
      var response = java.net.http.HttpClient.newHttpClient()
          .send(request.build(), java.net.http.HttpResponse.BodyHandlers.ofString());
      return new ApiClient.Result(response.statusCode(), response.body());
    } catch (Exception e) {
      throw new IllegalStateException("webhook delivery failed", e);
    } finally {
      caller.forgetSession();
    }
  }

  @Test
  void aVerifiedDeliveryStartsTheWorkflowItIsAttachedTo() {
    UUID workflowId = approvedWorkflow("On ticket raised");
    String token = subscribeWebhook(workflowId);
    String body = "{\"ticket\":\"TCK-9\"}";

    var accepted = deliver(token, body, sign(body));

    assertThat(accepted.status()).as(accepted.body()).isEqualTo(202);
    var run = runs.findByUserIdOrderByCreatedAtDesc(userId).stream()
        .filter(r -> workflowId.equals(r.getWorkflowId())).findFirst().orElseThrow();
    assertThat(run.getTriggerSource()).startsWith("webhook:");
    assertThat(api.get("/api/activity?action=webhook.received").json()).isNotEmpty();
  }

  @Test
  void anUnsignedOrBadlySignedDeliveryIsRecordedAndDroppedWithoutRunningAnything() {
    UUID workflowId = approvedWorkflow("On ticket raised");
    String token = subscribeWebhook(workflowId);
    String body = "{\"ticket\":\"TCK-10\"}";

    assertThat(deliver(token, body, null).status()).isEqualTo(403);
    assertThat(deliver(token, body, sign("a different body")).status()).isEqualTo(403);

    assertThat(runs.findByUserIdOrderByCreatedAtDesc(userId))
        .as("an unverified delivery triggers nothing")
        .noneMatch(r -> workflowId.equals(r.getWorkflowId()));
    assertThat(api.get("/api/activity?action=webhook.rejected").json()).isNotEmpty();
  }

  @Test
  void anUnknownEndpointTokenIsNotAnEndpoint() {
    assertThat(deliver("not-a-real-token", "{}", "abc").status()).isEqualTo(404);
  }

  @Test
  void aDisabledEndpointStopsAccepting() {
    UUID workflowId = approvedWorkflow("On ticket raised");
    String token = subscribeWebhook(workflowId);
    String id = api.get("/api/webhook-endpoints").json().get(0).path("id").asText();

    assertThat(api.delete("/api/webhook-endpoints/" + id).ok()).isTrue();

    String body = "{\"ticket\":\"TCK-11\"}";
    assertThat(deliver(token, body, sign(body)).status()).isEqualTo(403);
  }

  // ── taking the data out, and destroying it ───────────────────────────────

  @Test
  void theExportContainsTheUsersOwnDataAndNoCredential() {
    model.scriptFor("Jizza", LlmResponse.text("Noted."));
    api.post("/api/chat", Map.of("message", "remember the milk"));
    api.post("/api/connections", Map.of(
        "serviceName", "Ticketing", "authScheme", "API_KEY",
        "clientSecret", "top-secret-api-key", "baseUrl", "https://tickets.example.com"));

    var export = api.get("/api/data/export");

    assertThat(export.ok()).as(export.body()).isTrue();
    JsonNode body = export.json();
    assertThat(body.path("account").path("email").asText()).isEqualTo(email);
    assertThat(body.path("agents")).hasSize(4);
    assertThat(body.path("conversations").get(0).path("messages").toString())
        .contains("remember the milk");
    assertThat(body.path("activity")).isNotEmpty();

    var connection = body.path("connections").get(0);
    assertThat(connection.path("credentialsStored").asBoolean()).isTrue();
    assertThat(export.body())
        .as("an export is a file that leaves the machine; no live credential belongs in one")
        .doesNotContain("top-secret-api-key");
  }

  @Test
  void hardDeleteWithoutTheTypedConfirmationChangesNothing() {
    var refused = api.delete("/api/data?confirm=yes");

    assertThat(refused.status()).isEqualTo(400);
    assertThat(users.findById(userId)).isPresent();
  }

  @Test
  void hardDeleteReallyRemovesEverythingTheUserOwns() {
    model.scriptFor("Jizza", LlmResponse.text("Noted."));
    api.post("/api/chat", Map.of("message", "something personal"));
    api.post("/api/tasks", Map.of(
        "title", "Later", "agentId", agentId("professional").toString(),
        "scheduleType", "ONE_TIME", "runAt", Instant.now().plusSeconds(3600).toString()));
    List<UUID> agentIds = agents.list(userId).stream().map(AgentDto::id).toList();

    var deleted = api.delete("/api/data?confirm=DELETE%20EVERYTHING");

    assertThat(deleted.ok()).as(deleted.body()).isTrue();
    assertThat(users.findById(userId)).as("the account row is gone").isEmpty();
    assertThat(auditEntries.findByUserIdOrderBySeqAsc(userId)).isEmpty();
    assertThat(notifications.findByUserIdOrderByCreatedAtDesc(userId)).isEmpty();
    assertThat(workflows.findByUserIdOrderByCreatedAtDesc(userId)).isEmpty();
    for (UUID agentId : agentIds) {
      assertThat(knowledge.findByAgentIdOrderByUpdatedAtDesc(agentId,
          org.springframework.data.domain.PageRequest.of(0, 10))).isEmpty();
    }
    // The session that did it is no longer a session.
    assertThat(api.get("/api/agents").status()).isEqualTo(401);
  }
}
