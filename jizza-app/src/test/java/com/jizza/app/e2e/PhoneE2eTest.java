package com.jizza.app.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.devicesim.SimDevice;
import com.jizza.engine.api.dto.AgentDto;
import com.jizza.engine.api.enums.NotificationKind;
import com.jizza.engine.api.llm.LlmResponse;
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
  @Autowired private E2eSupport.ScriptedLlmClient model;

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
    // Turning someone else's (or no) workflow off is not found either.
    String off = Json.write(Map.of("enabled", false));
    String enabledPath = "/agent/v1/me/workflows/" + UUID.randomUUID() + "/enabled";
    assertThat(post(enabledPath, off, signedHeaders("POST", enabledPath, off)).statusCode())
        .isEqualTo(404);
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

    // An agent can be turned off and on from the phone (this phone has full access by now).
    String off = Json.write(Map.of("enabled", false));
    String agentPath = "/agent/v1/me/agents/" + agentId + "/enabled";
    var disabled = post(agentPath, off, signedHeaders("POST", agentPath, off));
    assertThat(disabled.statusCode()).as(disabled.body()).isEqualTo(200);
    assertThat(Json.read(disabled.body()).path("enabled").asBoolean()).isFalse();
    String on = Json.write(Map.of("enabled", true));
    var enabled = post(agentPath, on, signedHeaders("POST", agentPath, on));
    assertThat(Json.read(enabled.body()).path("enabled").asBoolean()).isTrue();

    // Resuming someone else's (or no) run is not found.
    String resume = Json.write(Map.of("instruction", "go on"));
    String resumePath = "/agent/v1/me/runs/" + UUID.randomUUID() + "/resume";
    assertThat(post(resumePath, resume, signedHeaders("POST", resumePath, resume)).statusCode())
        .isEqualTo(404);
  }

  @Test
  void askAnswersFromTheOwnersFactsAndOnlyOffersCommandsItMay() throws Exception {
    pairPhone();
    model.reset();
    model.script(LlmResponse.text(
        "{\"answer\": \"Nothing failed today.\", \"command\": {\"type\": \"PAUSE_ALL\"}}"));
    String body = Json.write(Map.of("question", "What failed today?", "tz", "Asia/Kolkata"));
    var r = postJsonWithToken("/agent/v1/me/ask", body);
    assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
    JsonNode answer = Json.read(r.body());
    assertThat(answer.path("answer").asText()).isEqualTo("Nothing failed today.");
    assertThat(answer.path("command").path("type").asText()).isEqualTo("PAUSE_ALL");
    // The model saw the person's facts.
    assertThat(model.everythingTheModelSaw()).contains("waitingForYou").contains("Asia/Kolkata");

    // A workflow that isn't the person's is never offered.
    model.script(LlmResponse.text("{\"answer\": \"Sure.\", \"command\": {\"type\": "
        + "\"RUN_WORKFLOW\", \"workflowId\": \"" + UUID.randomUUID() + "\"}}"));
    var other = Json.read(postJsonWithToken("/agent/v1/me/ask", body).body());
    assertThat(other.path("command").isNull() || other.path("command").isMissingNode()).isTrue();

    // Nothing changed: asking only reads.
    assertThat(Json.read(get("/agent/v1/me/today").body()).path("paused").asBoolean()).isFalse();

    // An empty question is refused.
    var empty = postJsonWithToken("/agent/v1/me/ask", Json.write(Map.of("question", " ")));
    assertThat(empty.statusCode()).isEqualTo(400);
  }

  @Test
  void helpGuidesAreTheWebsOwnAndOpeningAPushedItemMarksItRead() throws Exception {
    pairPhone();
    var list = get("/agent/v1/me/help");
    assertThat(list.statusCode()).as(list.body()).isEqualTo(200);
    JsonNode guides = Json.read(list.body());
    assertThat(guides.isArray()).isTrue();
    JsonNode signIn = null;
    for (JsonNode g : guides) if (g.path("slug").asText().equals("smart-sign-in")) signIn = g;
    assertThat(signIn).as(list.body()).isNotNull();
    assertThat(signIn.path("title").asText()).isNotBlank();
    assertThat(signIn.has("body")).isFalse();

    var guide = get("/agent/v1/me/help/smart-sign-in");
    assertThat(guide.statusCode()).as(guide.body()).isEqualTo(200);
    assertThat(Json.read(guide.body()).path("body").asText()).contains("## ").doesNotContain("summary:");
    assertThat(get("/agent/v1/me/help/no-such-guide").statusCode()).isEqualTo(404);

    UUID userId = users.findByEmailIgnoreCase(email).orElseThrow().getId();
    UUID agentId = agents.byKey(userId, "finance").map(AgentDto::id).orElseThrow();
    UUID itemId = bus.raise(NotificationKind.PERMISSION_REQUEST, agentId,
        new NotificationContext(userId, "Pay Acme?", Json.obj(), null, null, null, null)).id();
    String path = "/agent/v1/me/notifications/" + itemId + "/read";
    assertThat(post(path, "{}", null).statusCode()).isEqualTo(401);
    var read = post(path, "{}", signedHeaders("POST", path, "{}"));
    assertThat(read.statusCode()).as(read.body()).isEqualTo(200);
    assertThat(Json.read(read.body()).path("status").asText()).isEqualTo("READ");
  }

  // ---- Vita medicine reminders (plan step 6d-1) -------------------------------------------------

  /** A person with a medicine every two hours, so today always has slots; returns the person id. */
  private String personWithMedicine(ApiClient account, String name) {
    String person = account.post("/api/people", Map.of("displayName", name, "kind", "parent", "type", "individual"))
        .json().path("id").asText();
    var made = account.post("/api/vita/people/" + person + "/medicines", Map.of("name", "metformin",
        "schedule", Map.of("times", java.util.List.of("00:00", "02:00", "04:00", "06:00", "08:00", "10:00", "12:00",
            "14:00", "16:00", "18:00", "20:00", "22:00"))));
    assertThat(made.ok()).as(made.body()).isTrue();
    return person;
  }

  @Test
  void thePhoneListsTodaysMedicineSlotsWithoutNamesAndMarksThemOnce() throws Exception {
    pairPhone();
    String father = personWithMedicine(web, "Father");
    var today = get("/agent/v1/me/vita/today?tz=Asia/Kolkata&hours=48");
    assertThat(today.statusCode()).as(today.body()).isEqualTo(200);
    JsonNode t = Json.read(today.body());
    assertThat(t.path("showNames").asBoolean()).isFalse();
    JsonNode slots = t.path("slots");
    assertThat(slots.size()).isGreaterThan(0);
    JsonNode first = slots.get(0);
    assertThat(first.path("personId").asText()).isEqualTo(father);
    assertThat(first.path("personName").asText()).isEqualTo("Father");
    assertThat(first.path("zone").asText()).isEqualTo("Asia/Kolkata");
    assertThat(first.path("doses").get(0).path("medicineName").isNull()).isTrue();
    assertThat(today.body()).doesNotContain("metformin");

    String body = Json.write(Map.of("personId", father, "dueAt", first.path("dueAt").asText(), "action", "taken"));
    String path = "/agent/v1/me/vita/slots/act";
    var marked = post(path, body, signedHeaders("POST", path, body));
    assertThat(marked.statusCode()).as(marked.body()).isEqualTo(200);
    JsonNode slot = Json.read(marked.body());
    assertThat(slot.path("status").asText()).isEqualTo("taken");
    String markedAt = slot.path("markedAt").asText();
    assertThat(markedAt).isNotBlank();

    // The same action sent again (a new signature, as a retry would carry) changes nothing.
    var again = post(path, body, signedHeaders("POST", path, body));
    assertThat(again.statusCode()).as(again.body()).isEqualTo(200);
    assertThat(Json.read(again.body()).path("markedAt").asText()).isEqualTo(markedAt);
    // Skipping a slot already taken leaves the first mark.
    String skip = Json.write(Map.of("personId", father, "dueAt", first.path("dueAt").asText(), "action", "skipped"));
    assertThat(Json.read(post(path, skip, signedHeaders("POST", path, skip)).body()).path("status").asText()).isEqualTo("taken");

    // One dose of the next slot, snoozed twice: the second press is a no-op.
    String dose = slots.get(1).path("doses").get(0).path("id").asText();
    String dosePath = "/agent/v1/me/vita/doses/" + dose + "/act";
    String snooze = Json.write(Map.of("action", "snoozed", "snoozeMinutes", 15));
    var s1 = post(dosePath, snooze, signedHeaders("POST", dosePath, snooze));
    assertThat(s1.statusCode()).as(s1.body()).isEqualTo(200);
    var s2 = post(dosePath, snooze, signedHeaders("POST", dosePath, snooze));
    assertThat(s2.statusCode()).as(s2.body()).isEqualTo(200);
    assertThat(Json.read(s2.body()).path("doses").get(0).path("version").asLong())
        .isEqualTo(Json.read(s1.body()).path("doses").get(0).path("version").asLong());
    // The web sees the same marks.
    assertThat(web.get("/api/vita/people/" + father + "/doses?from=" + first.path("localDate").asText()
        + "&to=" + first.path("localDate").asText()).body()).contains("\"status\":\"taken\"");
  }

  @Test
  void markingAMedicineNeedsASignatureApproveAccessAndOnesOwnDose() throws Exception {
    UUID deviceId = pairPhone();
    String father = personWithMedicine(web, "Father");
    JsonNode slot = Json.read(get("/agent/v1/me/vita/today?tz=Asia/Kolkata&hours=48").body()).path("slots").get(0);
    String body = Json.write(Map.of("personId", father, "dueAt", slot.path("dueAt").asText(), "action", "taken"));
    String path = "/agent/v1/me/vita/slots/act";
    assertThat(post(path, body, null).statusCode()).as("unsigned").isEqualTo(401);

    // Another account's dose and person read as missing.
    ApiClient other = new ApiClient(port);
    other.prime();
    String otherEmail = "phone-other-" + UUID.randomUUID() + "@jizza.local";
    assertThat(other.post("/api/auth/register", Map.of("email", otherEmail, "password", PASSWORD)).ok()).isTrue();
    assertThat(other.post("/api/auth/login", Map.of("email", otherEmail, "password", PASSWORD)).ok()).isTrue();
    String theirs = personWithMedicine(other, "Their mother");
    var date = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"));
    JsonNode theirSlot = other.get("/api/vita/people/" + theirs + "/doses?from=" + date + "&to=" + date.plusDays(1))
        .json().path("slots").get(0);
    String theirDose = "/agent/v1/me/vita/doses/" + theirSlot.path("doses").get(0).path("id").asText() + "/act";
    String taken = Json.write(Map.of("action", "taken"));
    assertThat(post(theirDose, taken, signedHeaders("POST", theirDose, taken)).statusCode()).isEqualTo(404);
    String theirBody = Json.write(Map.of("personId", theirs, "dueAt", theirSlot.path("dueAt").asText(), "action", "taken"));
    assertThat(post(path, theirBody, signedHeaders("POST", path, theirBody)).statusCode()).isEqualTo(404);
    assertThat(other.get("/api/vita/people/" + theirs + "/doses?from=" + date + "&to=" + date.plusDays(1)).body())
        .doesNotContain("\"status\":\"taken\"");

    // Read only: marking is refused, reading still works.
    stepUp();
    assertThat(web.put("/api/devices/" + deviceId + "/phone-access", Map.of("access", "READ")).ok()).isTrue();
    assertThat(post(path, body, signedHeaders("POST", path, body)).statusCode()).isEqualTo(403);
    assertThat(get("/agent/v1/me/vita/today?tz=Asia/Kolkata").statusCode()).isEqualTo(200);
  }


  // ---- Vita health data from the phone (plan step 9a) ------------------------------------------

  private JsonNode signedPost(String path, Object body) throws Exception {
    String json = Json.write(body);
    var r = post(path, json, signedHeaders("POST", path, json));
    assertThat(r.statusCode()).as("%s: %s", path, r.body()).isEqualTo(200);
    return Json.read(r.body());
  }

  private String me() {
    return person(web, "Me");
  }

  private static String person(ApiClient account, String name) {
    var made = account.post("/api/people", Map.of("displayName", name, "kind", "parent", "type", "individual"));
    assertThat(made.ok()).as(made.body()).isTrue();
    return made.json().path("id").asText();
  }

  private java.util.List<Map<String, Object>> batch() {
    String day = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")).minusDays(1).toString();
    Instant woke = Instant.now().minus(java.time.Duration.ofHours(3)).truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
    return java.util.List.of(
        Map.of("externalId", "hc-steps-" + day, "type", "steps", "date", day, "value", 8432),
        Map.of("externalId", "hc-sleep-1", "type", "sleep", "startedAt", woke.minus(java.time.Duration.ofMinutes(450)).toString(),
            "endedAt", woke.toString()),
        Map.of("externalId", "hc-walk-1", "type", "workout", "workoutType", "walking",
            "startedAt", woke.plus(java.time.Duration.ofMinutes(30)).toString(), "endedAt", woke.plus(java.time.Duration.ofMinutes(65)).toString()));
  }

  @Test
  void aSignedBatchIsSavedOnceAndOnlyForTheTypesGranted() throws Exception {
    pairPhone();
    String me = me();
    assertThat(me).isNotBlank();
    // Unsigned grants and batches are refused.
    String grantBody = Json.write(Map.of("personId", me, "source", "health_connect", "dataTypes",
        java.util.List.of("steps", "sleep")));
    assertThat(post("/agent/v1/me/vita/connectors", grantBody, null).statusCode()).isEqualTo(401);
    // The phone learns whose data it may send.
    JsonNode people = Json.read(get("/agent/v1/me/vita/people").body());
    assertThat(people.toString()).contains(me);
    for (JsonNode person : people) {
      if (person.path("id").asText().equals(me)) {
        assertThat(person.path("name").asText()).isEqualTo("Me");
        assertThat(person.path("canSendHealthData").asBoolean()).isTrue();
        assertThat(person.path("shared").asBoolean()).isFalse();
      }
    }
    JsonNode grant = signedPost("/agent/v1/me/vita/connectors", Map.of("personId", me, "source", "health_connect",
        "dataTypes", java.util.List.of("steps", "sleep")));
    String grantId = grant.path("id").asText();
    assertThat(grant.path("deviceName").asText()).isEqualTo("Chiam's Pixel");
    assertThat(grant.path("dataTypes")).hasSize(2);

    JsonNode first = signedPost("/agent/v1/me/vita/ingest", Map.of("personId", me, "grantId", grantId, "records", batch()));
    assertThat(first.path("added").asInt()).isEqualTo(2);
    // Workouts were not turned on, so that record is refused on its own.
    assertThat(first.path("refused").asInt()).isEqualTo(1);
    assertThat(first.path("results").get(2).path("reason").asText()).contains("not turned on");

    // The same batch again adds nothing.
    JsonNode again = signedPost("/agent/v1/me/vita/ingest", Map.of("personId", me, "grantId", grantId, "records", batch()));
    assertThat(again.path("added").asInt()).isZero();
    assertThat(again.path("unchanged").asInt()).isEqualTo(2);
    JsonNode steps = web.get("/api/vita/people/" + me + "/measurements?kind=steps").json();
    assertThat(steps).hasSize(1);
    assertThat(steps.get(0).path("source").asText()).isEqualTo("health_connect");
    assertThat(steps.get(0).path("device").asText()).isEqualTo("Chiam's Pixel");
    assertThat(steps.get(0).path("value").decimalValue()).isEqualByComparingTo("8432");

    // The phone lists its connection with what it wrote.
    JsonNode mine = Json.read(get("/agent/v1/me/vita/connectors").body());
    assertThat(mine).hasSize(1);
    assertThat(mine.get(0).path("rowsWritten").asInt()).isEqualTo(2);
    assertThat(mine.get(0).path("lastSyncAt").isNull()).isFalse();
  }

  @Test
  void anotherPhoneOrAccountCannotSendThroughAConnection() throws Exception {
    pairPhone();
    String me = me();
    // A connection made on the web belongs to no phone, so the phone cannot use it.
    String webGrant = web.post("/api/vita/connectors", Map.of("personId", me, "source", "health_connect",
        "dataTypes", java.util.List.of("steps"))).json().path("id").asText();
    String body = Json.write(Map.of("personId", me, "grantId", webGrant, "records", batch()));
    String path = "/agent/v1/me/vita/ingest";
    assertThat(post(path, body, signedHeaders("POST", path, body)).statusCode()).isEqualTo(404);

    // Another account's person reads as missing.
    ApiClient other = new ApiClient(port);
    other.prime();
    String otherEmail = "phone-other-" + UUID.randomUUID() + "@jizza.local";
    assertThat(other.post("/api/auth/register", Map.of("email", otherEmail, "password", PASSWORD)).ok()).isTrue();
    assertThat(other.post("/api/auth/login", Map.of("email", otherEmail, "password", PASSWORD)).ok()).isTrue();
    String theirs = person(other, "Their mother");
    String grant = Json.write(Map.of("personId", theirs, "source", "health_connect", "dataTypes", java.util.List.of("steps")));
    String gp = "/agent/v1/me/vita/connectors";
    assertThat(post(gp, grant, signedHeaders("POST", gp, grant)).statusCode()).isEqualTo(404);
    String theirGrant = other.post("/api/vita/connectors", Map.of("personId", theirs, "source", "health_connect",
        "dataTypes", java.util.List.of("steps"))).json().path("id").asText();
    String theirBatch = Json.write(Map.of("personId", theirs, "grantId", theirGrant, "records", batch()));
    assertThat(post(path, theirBatch, signedHeaders("POST", path, theirBatch)).statusCode()).isEqualTo(404);
    assertThat(other.get("/api/vita/people/" + theirs + "/measurements?kind=steps").json()).isEmpty();
  }

  @Test
  void revokingStopsSyncAndCanDeleteWhatThePhoneSent() throws Exception {
    pairPhone();
    String me = me();
    String grantId = signedPost("/agent/v1/me/vita/connectors", Map.of("personId", me, "source", "health_connect",
        "dataTypes", java.util.List.of("steps", "sleep", "workout"))).path("id").asText();
    assertThat(signedPost("/agent/v1/me/vita/ingest", Map.of("personId", me, "grantId", grantId, "records", batch()))
        .path("added").asInt()).isEqualTo(3);

    // Turning sleep off keeps the connection for the rest and deletes only sleep.
    String off = "/agent/v1/me/vita/connectors/" + grantId;
    var narrowed = delete(off + "?personId=" + me + "&types=sleep&deleteData=true", signedHeaders("DELETE", off, ""));
    assertThat(narrowed.statusCode()).as(narrowed.body()).isEqualTo(200);
    JsonNode n = Json.read(narrowed.body());
    assertThat(n.path("revokedAt").isNull()).isTrue();
    assertThat(n.path("rowsDeleted").asInt()).isEqualTo(1);
    assertThat(n.path("dataTypes")).hasSize(2);
    assertThat(web.get("/api/vita/people/" + me + "/measurements?kind=sleep").json()).isEmpty();

    // Revoking the rest stops sync; the answer says what is still there so the screen can offer to delete.
    var revoked = delete(off + "?personId=" + me, signedHeaders("DELETE", off, ""));
    assertThat(revoked.statusCode()).as(revoked.body()).isEqualTo(200);
    assertThat(Json.read(revoked.body()).path("revokedAt").isNull()).isFalse();
    assertThat(Json.read(revoked.body()).path("rowsWritten").asInt()).isEqualTo(2);
    String body = Json.write(Map.of("personId", me, "grantId", grantId, "records", batch()));
    String path = "/agent/v1/me/vita/ingest";
    assertThat(post(path, body, signedHeaders("POST", path, body)).statusCode()).isEqualTo(409);
    var deleted = delete(off + "?personId=" + me + "&deleteData=true", signedHeaders("DELETE", off, ""));
    assertThat(Json.read(deleted.body()).path("rowsDeleted").asInt()).isEqualTo(2);
    assertThat(web.get("/api/vita/people/" + me + "/measurements?kind=steps").json()).isEmpty();
    assertThat(web.get("/api/vita/people/" + me + "/measurements?kind=workout").json()).isEmpty();
  }

  @Test
  void aBloodPressureTypedAndThenSentByThePhoneIsOneRow() throws Exception {
    pairPhone();
    String me = me();
    Instant at = Instant.now().minus(java.time.Duration.ofMinutes(10)).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
    var typed = web.post("/api/vita/people/" + me + "/measurements", Map.of("kind", "bp", "value", 128, "value2", 82,
        "takenAt", at.toString()));
    assertThat(typed.ok()).as(typed.body()).isTrue();
    String grantId = signedPost("/agent/v1/me/vita/connectors", Map.of("personId", me, "source", "health_connect",
        "dataTypes", java.util.List.of("bp"))).path("id").asText();
    JsonNode sent = signedPost("/agent/v1/me/vita/ingest", Map.of("personId", me, "grantId", grantId, "records",
        java.util.List.of(Map.of("externalId", "hc-bp-1", "type", "bp", "value", 128.0, "value2", 82.0,
            "takenAt", at.plusSeconds(90).toString()))));
    assertThat(sent.path("duplicate").asInt()).isEqualTo(1);
    assertThat(sent.path("results").get(0).path("id").asText()).isEqualTo(typed.json().path("id").asText());
    assertThat(web.get("/api/vita/people/" + me + "/measurements?kind=bp").json()).hasSize(1);
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

  private HttpResponse<String> delete(String path, Map<String, String> headers) throws Exception {
    var b = HttpRequest.newBuilder(uri(path))
        .header("Authorization", "Bearer " + phone.token())
        .DELETE();
    if (headers != null) headers.forEach(b::header);
    return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> postJsonWithToken(String path, String body) throws Exception {
    return post(path, body, null);
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
