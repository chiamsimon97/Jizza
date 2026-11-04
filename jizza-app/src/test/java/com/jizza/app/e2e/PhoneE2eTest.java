package com.jizza.app.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.devicesim.SimDevice;
import com.jizza.engine.api.dto.AgentDto;
import com.jizza.engine.api.enums.NotificationKind;
import com.jizza.engine.api.notification.NotificationBus;
import com.jizza.engine.api.notification.NotificationContext;
import com.jizza.engine.api.orchestrator.AgentDirectory;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.persistence.repository.UserRepository;
import com.jizza.engine.security.mfa.TotpService;
import com.jizza.platform.api.rest.PhoneRequests;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Acceptance: the Jizza app on a person's own phone. It reads their Jizza under
 * {@code /agent/v1/me}, acts only on requests signed with its device key and within the access the
 * person gave it, refuses shared machines, and can sign in on the phone itself.
 */
@org.junit.jupiter.api.condition.EnabledIf(
    value = "com.jizza.engine.persistence.support.TestDatabase#available",
    disabledReason = "no PostgreSQL: set JIZZA_TEST_DB_URL or start Docker")
class PhoneE2eTest extends E2eSupport {

  private static final String PASSWORD = "Correct-Horse-92";

  @LocalServerPort private int port;
  @Autowired private TotpService totp;
  @Autowired private UserRepository users;
  @Autowired private NotificationBus bus;
  @Autowired private AgentDirectory agents;

  private ApiClient web;
  private String email;
  private SimDevice phone;
  private final HttpClient http = HttpClient.newHttpClient();

  @BeforeEach
  void setUp() {
    web = new ApiClient(port);
    web.prime();
    email = "phone-" + UUID.randomUUID() + "@jizza.local";
    assertThat(web.post("/api/auth/register", Map.of("email", email, "password", PASSWORD)).ok())
        .isTrue();
    assertThat(web.post("/api/auth/login", Map.of("email", email, "password", PASSWORD)).ok())
        .isTrue();
  }

  @AfterEach
  void tearDown() {
    if (phone != null) phone.close();
  }

  @Test
  void aPairedPhoneReadsItsOwnersJizza() throws Exception {
    pairPhone();

    var me = get("/agent/v1/me");
    assertThat(me.statusCode()).as(me.body()).isEqualTo(200);
    JsonNode who = Json.read(me.body());
    assertThat(who.path("deviceName").asText()).isEqualTo("Chiam's Pixel");
    assertThat(who.path("access").asText()).isEqualTo("APPROVE");

    var today = get("/agent/v1/me/today?tz=Asia/Kolkata");
    assertThat(today.statusCode()).as(today.body()).isEqualTo(200);
    JsonNode t = Json.read(today.body());
    assertThat(t.path("waiting").path("needsYou").asInt()).isZero();
    assertThat(t.path("runs").path("total").asLong()).isZero();
    assertThat(t.path("devices").path("total").asInt()).isEqualTo(1);
    assertThat(t.path("paused").asBoolean()).isFalse();

    for (String path : new String[] {"/agent/v1/me/notifications", "/agent/v1/me/runs",
        "/agent/v1/me/workflows", "/agent/v1/me/usage", "/agent/v1/me/activity",
        "/agent/v1/me/guardrails", "/agent/v1/me/devices", "/agent/v1/me/agents"}) {
      var r = get(path);
      assertThat(r.statusCode()).as("%s: %s", path, r.body()).isEqualTo(200);
    }
    // Agents come without their instructions.
    assertThat(get("/agent/v1/me/agents").body()).doesNotContain("systemPrompt");
  }

  @Test
  void withoutADeviceTokenNothingIsShown() throws Exception {
    var r = http.send(HttpRequest.newBuilder(uri("/agent/v1/me")).GET().build(),
        HttpResponse.BodyHandlers.ofString());
    assertThat(r.statusCode()).isEqualTo(401);
  }

  @Test
  void changesMustBeSignedByThePhonesKeyAndUsedOnce() throws Exception {
    pairPhone();
    String body = Json.write(Map.of("paused", true));

    assertThat(post("/agent/v1/me/pause", body, null).statusCode()).isEqualTo(401);

    // A signature over a different body is refused.
    var wrongBody = signedHeaders("POST", "/agent/v1/me/pause", Json.write(Map.of("paused", false)));
    assertThat(post("/agent/v1/me/pause", body, wrongBody).statusCode()).isEqualTo(401);

    var headers = signedHeaders("POST", "/agent/v1/me/pause", body);
    var paused = post("/agent/v1/me/pause", body, headers);
    assertThat(paused.statusCode()).as(paused.body()).isEqualTo(200);
    assertThat(Json.read(get("/agent/v1/me/today").body()).path("paused").asBoolean()).isTrue();

    // The same signed request can't be sent twice.
    assertThat(post("/agent/v1/me/pause", body, headers).statusCode()).isEqualTo(401);

    // An old signature is refused.
    String old = String.valueOf(Instant.now().minusSeconds(600).toEpochMilli());
    String nonce = UUID.randomUUID().toString();
    var stale = Map.of(PhoneRequests.SIGNED_AT, old, PhoneRequests.NONCE, nonce,
        PhoneRequests.SIGNATURE, phone.sign(PhoneRequests.signedText("POST", "/agent/v1/me/pause",
            old, nonce, body.getBytes(StandardCharsets.UTF_8))));
    assertThat(post("/agent/v1/me/pause", body, stale).statusCode()).isEqualTo(401);
  }

  @Test
  void whatThePhoneMayDoFollowsItsAccess() throws Exception {
    UUID deviceId = pairPhone();

    // Approve (the default) may pull Pause all but not start things again.
    String resume = Json.write(Map.of("paused", false));
    var refused = post("/agent/v1/me/pause", resume, signedHeaders("POST", "/agent/v1/me/pause", resume));
    assertThat(refused.statusCode()).as(refused.body()).isEqualTo(403);
    assertThat(refused.body()).contains("phone-access");

    // An answer passes the phone checks and reaches the inbox, which has no such item.
    String answer = Json.write(Map.of("kind", "APPROVE"));
    String path = "/agent/v1/me/notifications/" + UUID.randomUUID() + "/answer";
    assertThat(post(path, answer, signedHeaders("POST", path, answer)).statusCode()).isEqualTo(404);

    // Read only: answering is refused before anything else.
    stepUp();
    assertThat(web.put("/api/devices/" + deviceId + "/phone-access", Map.of("access", "READ")).ok())
        .isTrue();
    assertThat(post(path, answer, signedHeaders("POST", path, answer)).statusCode()).isEqualTo(403);
    assertThat(get("/agent/v1/me/runs").statusCode()).isEqualTo(200);

    // Full: resuming works.
    assertThat(web.put("/api/devices/" + deviceId + "/phone-access", Map.of("access", "FULL")).ok())
        .isTrue();
    assertThat(web.get("/api/devices/" + deviceId + "/phone-access").json().path("access").asText())
        .isEqualTo("FULL");
    var resumed = post("/agent/v1/me/pause", resume, signedHeaders("POST", "/agent/v1/me/pause", resume));
    assertThat(resumed.statusCode()).as(resumed.body()).isEqualTo(200);

    // Running someone else's (or no) workflow is not found.
    String run = Json.write(Map.of("input", Map.of()));
    String runPath = "/agent/v1/me/workflows/" + UUID.randomUUID() + "/run";
    assertThat(post(runPath, run, signedHeaders("POST", runPath, run)).statusCode()).isEqualTo(404);
  }

  @Test
  void aSharedMachineSeesNobodysJizza() throws Exception {
    stepUp();
    var token = web.post("/api/devices/enrollment-tokens", Map.of("name", "Kiosk", "uses", 1));
    assertThat(token.ok()).as(token.body()).isTrue();
    phone = new SimDevice("http://localhost:" + port, true);
    phone.enrollWithToken(token.json().path("token").asText(), "Front desk tablet", "android");
    phone.signIn();
    var r = get("/agent/v1/me");
    assertThat(r.statusCode()).isEqualTo(401);
    assertThat(r.body()).contains("device_not_personal");
  }

  @Test
  void signingInOnThePhoneNeedsTwoStepSignInAndEndsInAPairing() throws Exception {
    // Without two-step sign-in a password alone can't pair a phone.
    var noMfa = postJson("/agent/v1/login", Json.write(Map.of("email", email, "password", PASSWORD)));
    assertThat(noMfa.statusCode()).as(noMfa.body()).isEqualTo(403);
    assertThat(noMfa.body()).contains("two-step-required");

    String secret = enrolMfa();

    var wrong = postJson("/agent/v1/login", Json.write(Map.of("email", email, "password", "nope-nope-1")));
    assertThat(wrong.statusCode()).isEqualTo(401);

    var first = postJson("/agent/v1/login", Json.write(Map.of("email", email, "password", PASSWORD)));
    assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
    JsonNode step = Json.read(first.body());
    assertThat(step.path("mfaRequired").asBoolean()).isTrue();

    var second = postJson("/agent/v1/login/mfa", Json.write(Map.of(
        "mfaToken", step.path("mfaToken").asText(), "code", freshCode(secret))));
    assertThat(second.statusCode()).as(second.body()).isEqualTo(200);
    JsonNode done = Json.read(second.body());
    String code = done.path("pairingCode").asText();
    assertThat(code).hasSize(6);
    assertThat(done.path("pairUrl").asText()).startsWith("jizza://pair?code=" + code);

    phone = new SimDevice("http://localhost:" + port, true);
    phone.enrollWithCode(code, "Phone sign-in", "android");
    phone.signIn();
    var me = get("/agent/v1/me");
    assertThat(me.statusCode()).as(me.body()).isEqualTo(200);
    assertThat(Json.read(me.body()).path("deviceName").asText()).isEqualTo("Phone sign-in");
  }

  @Test
  void alwaysAllowSavesARuleAndApprovesButNeedsFullAccess() throws Exception {
    UUID deviceId = pairPhone();
    UUID userId = users.findByEmailIgnoreCase(email).orElseThrow().getId();
    UUID agentId = agents.byKey(userId, "finance").map(AgentDto::id).orElseThrow();
    UUID itemId = bus.raise(NotificationKind.PERMISSION_REQUEST, agentId,
        new NotificationContext(userId, "Pay Acme?", Json.obj().put("tool", "pay_supplier"),
            null, null, null, null)).id();
    String path = "/agent/v1/me/notifications/" + itemId + "/always-allow";

    // Approve access may answer but not loosen a guardrail.
    var refused = post(path, "{}", signedHeaders("POST", path, "{}"));
    assertThat(refused.statusCode()).as(refused.body()).isEqualTo(403);

    stepUp();
    assertThat(web.put("/api/devices/" + deviceId + "/phone-access", Map.of("access", "FULL")).ok())
        .isTrue();
    var allowed = post(path, "{}", signedHeaders("POST", path, "{}"));
    assertThat(allowed.statusCode()).as(allowed.body()).isEqualTo(200);
    assertThat(Json.read(allowed.body()).path("status").asText()).isEqualTo("APPROVED");
    assertThat(get("/agent/v1/me/guardrails").body()).contains("pay_supplier").contains("ALLOW");

    // Twice is refused: it is already answered.
    assertThat(post(path, "{}", signedHeaders("POST", path, "{}")).statusCode()).isEqualTo(400);

    // An information item can't be always allowed.
    UUID info = bus.raise(NotificationKind.INFO, agentId,
        new NotificationContext(userId, "FYI", Json.obj().put("tool", "pay_supplier"),
            null, null, null, null)).id();
    String infoPath = "/agent/v1/me/notifications/" + info + "/always-allow";
    assertThat(post(infoPath, "{}", signedHeaders("POST", infoPath, "{}")).statusCode())
        .isEqualTo(400);

    // It can be archived, and brought back.
    String archivePath = "/agent/v1/me/notifications/" + info + "/archive";
    var archived = post(archivePath, "{}", signedHeaders("POST", archivePath, "{}"));
    assertThat(archived.statusCode()).as(archived.body()).isEqualTo(200);
    assertThat(Json.read(archived.body()).path("archivedAt").isNull()).isFalse();
    String back = Json.write(Map.of("archived", false));
    var unarchived = post(archivePath, back, signedHeaders("POST", archivePath, back));
    assertThat(Json.read(unarchived.body()).path("archivedAt").isNull()).isTrue();

    // Resuming someone else's (or no) run is not found.
    String resume = Json.write(Map.of("instruction", "go on"));
    String resumePath = "/agent/v1/me/runs/" + UUID.randomUUID() + "/resume";
    assertThat(post(resumePath, resume, signedHeaders("POST", resumePath, resume)).statusCode())
        .isEqualTo(404);
  }

  // ---- helpers ----------------------------------------------------------------------------------

  private UUID pairPhone() {
    stepUp();
    var pairing = web.post("/api/devices/pairings", Map.of());
    assertThat(pairing.ok()).as("pair: %s", pairing.body()).isTrue();
    phone = new SimDevice("http://localhost:" + port, true);
    phone.enrollWithCode(pairing.json().path("code").asText(), "Chiam's Pixel", "android");
    phone.signIn();
    return phone.deviceId();
  }

  private void stepUp() {
    var r = web.post("/api/auth/step-up", Map.of("password", PASSWORD));
    assertThat(r.ok()).as("step-up: %s", r.body()).isTrue();
  }

  private String enrolMfa() {
    stepUp();
    var setup = web.post("/api/auth/mfa/setup", null);
    assertThat(setup.ok()).as("mfa/setup: %s", setup.body()).isTrue();
    String secret = setup.json().path("secret").asText();
    var confirmed = web.post("/api/auth/mfa/confirm", Map.of("code", freshCode(secret)));
    assertThat(confirmed.ok()).as("mfa/confirm: %s", confirmed.body()).isTrue();
    return secret;
  }

  private String freshCode(String secret) {
    while (true) {
      Long last = users.findByEmailIgnoreCase(email).orElseThrow().getMfaLastStep();
      long now = Instant.now().getEpochSecond() / 30;
      for (long s = now - 1; s <= now + 1; s++) {
        if (last == null || s > last) return totp.code(secret, s * 30);
      }
      try {
        Thread.sleep(1_000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
    }
  }

  private Map<String, String> signedHeaders(String method, String path, String body) {
    String at = String.valueOf(System.currentTimeMillis());
    String nonce = UUID.randomUUID().toString();
    String text = PhoneRequests.signedText(method, path, at, nonce,
        body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8));
    return Map.of(PhoneRequests.SIGNED_AT, at, PhoneRequests.NONCE, nonce,
        PhoneRequests.SIGNATURE, phone.sign(text));
  }

  private HttpResponse<String> get(String path) throws Exception {
    return http.send(HttpRequest.newBuilder(uri(path))
            .header("Authorization", "Bearer " + phone.token()).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> post(String path, String body, Map<String, String> headers)
      throws Exception {
    var b = HttpRequest.newBuilder(uri(path))
        .header("Authorization", "Bearer " + phone.token())
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body));
    if (headers != null) headers.forEach(b::header);
    return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> postJson(String path, String body) throws Exception {
    return http.send(HttpRequest.newBuilder(uri(path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private URI uri(String path) {
    return URI.create("http://localhost:" + port + path);
  }
}
