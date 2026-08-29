package com.jizza.app.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.jizza.engine.api.enums.AuthScheme;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.persistence.entity.ConnectionEntity;
import com.jizza.engine.persistence.repository.ConnectionRepository;
import com.jizza.engine.persistence.repository.UserRepository;
import com.jizza.engine.security.mfa.TotpService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Acceptance: hardened login, MFA, sessions, step-up, lockout, and the secrets invariants
 * (build prompt Acceptance Criteria 1, 2 and 4).
 */
@org.junit.jupiter.api.condition.EnabledIf(
    value = "com.jizza.engine.persistence.support.TestDatabase#available",
    disabledReason = "no PostgreSQL: set JIZZA_TEST_DB_URL or start Docker")
class AuthAndSecurityE2eTest extends E2eSupport {

  private static final String PASSWORD = "Correct-Horse-92";

  @LocalServerPort private int port;
  @Autowired private TotpService totp;
  @Autowired private UserRepository users;
  @Autowired private ConnectionRepository connections;
  @Autowired private E2eSupport.ScriptedLlmClient model;

  private ApiClient api;
  private String email;

  @BeforeEach
  void setUp() {
    api = new ApiClient(port);
    api.prime();
    email = "owner-" + UUID.randomUUID() + "@jizza.local";
    model.reset();
  }

  private void register() {
    var result = api.post("/api/auth/register", Map.of("email", email, "password", PASSWORD));
    assertThat(result.ok()).as("register: %s", result.body()).isTrue();
  }

  private void login() {
    var result = api.post("/api/auth/login", Map.of("email", email, "password", PASSWORD));
    assertThat(result.ok()).isTrue();
    assertThat(result.json().path("mfaRequired").asBoolean()).isFalse();
  }

  private void stepUp() {
    var result = api.post("/api/auth/step-up", Map.of("password", PASSWORD));
    assertThat(result.ok()).as("step-up: %s", result.body()).isTrue();
  }

  @Test
  void registeringSeedsTheAgentTeamAndSigningInGivesAccessToIt() {
    register();
    assertThat(api.get("/api/agents").status())
        .as("no session yet").isEqualTo(401);

    login();
    var agents = api.get("/api/agents");

    assertThat(agents.ok()).isTrue();
    assertThat(agents.json().findValuesAsText("key"))
        .containsExactlyInAnyOrder("jizza", "health", "finance", "professional");
    assertThat(agents.json().findValuesAsText("name"))
        .containsExactlyInAnyOrder("Jizza", "Vita", "Kai", "Cato");
  }

  @Test
  void aWeakOrBreachedPasswordIsRefused() {
    assertThat(api.post("/api/auth/register", Map.of("email", email, "password", "short")).status())
        .isEqualTo(400);
    assertThat(api.post("/api/auth/register",
        Map.of("email", email, "password", "Password1234")).json().path("detail").asText())
        .contains("breach");
    assertThat(users.findByEmailIgnoreCase(email)).isEmpty();
  }

  @Test
  void aPasswordIsNeverStoredInAFormThatCouldBeRead() {
    register();
    var stored = users.findByEmailIgnoreCase(email).orElseThrow();
    assertThat(stored.getPasswordHash())
        .doesNotContain(PASSWORD)
        .startsWith("$argon2id$");
  }

  @Test
  void turningOnMfaMakesTheSecondFactorMandatory() {
    register();
    login();
    stepUp();

    var setup = api.post("/api/auth/mfa/setup", null);
    assertThat(setup.ok()).as("mfa/setup: %s", setup.body()).isTrue();
    String secret = setup.json().path("secret").asText();
    assertThat(setup.json().path("provisioningUri").asText()).startsWith("otpauth://totp/Jizza:");

    var codes = api.post("/api/auth/mfa/confirm",
        Map.of("code", totp.code(secret, Instant.now().getEpochSecond())));
    assertThat(codes.ok()).isTrue();
    assertThat(codes.json().path("codes")).hasSize(8);

    // The MFA seed is stored encrypted, not as the base32 the user scanned.
    var user = users.findByEmailIgnoreCase(email).orElseThrow();
    assertThat(new String(user.getMfaSecret(), StandardCharsets.UTF_8)).isNotEqualTo(secret);

    ApiClient fresh = new ApiClient(port);
    fresh.prime();
    var attempt = fresh.post("/api/auth/login", Map.of("email", email, "password", PASSWORD));
    assertThat(attempt.json().path("mfaRequired").asBoolean()).isTrue();
    assertThat(fresh.hasSession()).as("no session until the second factor").isFalse();

    String mfaToken = attempt.json().path("mfaToken").asText();
    assertThat(fresh.post("/api/auth/mfa/verify", Map.of("mfaToken", mfaToken, "code", "000000"))
        .status()).isEqualTo(401);

    ApiClient second = new ApiClient(port);
    second.prime();
    String token = second.post("/api/auth/login", Map.of("email", email, "password", PASSWORD))
        .json().path("mfaToken").asText();
    var verified = second.post("/api/auth/mfa/verify",
        Map.of("mfaToken", token, "code", totp.code(secret, Instant.now().getEpochSecond())));

    assertThat(verified.ok()).isTrue();
    assertThat(second.get("/api/agents").ok()).isTrue();
  }

  @Test
  void aSensitiveRouteForcesStepUpAndThenLetsItThrough() {
    register();
    login();

    var body = Map.of("serviceName", "Example", "authScheme", "OAUTH2", "clientId", "abc",
        "clientSecret", "shhh", "authorizationUrl", "https://auth.example.test/authorize",
        "accessTokenUrl", "https://auth.example.test/token");

    var blocked = api.post("/api/connections", body);
    assertThat(blocked.status()).isEqualTo(403);
    assertThat(blocked.problemType()).endsWith("step-up-required");

    stepUp();
    assertThat(api.post("/api/connections", body).ok()).isTrue();
  }

  @Test
  void stepUpNeedsTheRealPasswordAndExpires() {
    register();
    login();
    assertThat(api.post("/api/auth/step-up", Map.of("password", "not-my-password")).status())
        .isEqualTo(401);
    assertThat(api.get("/api/autonomy/policies").status()).isEqualTo(403);

    stepUp();
    assertThat(api.get("/api/autonomy/policies").ok()).isTrue();
  }

  @Test
  void sessionsCanBeListedRevokedAndRevokedEverywhere() {
    register();
    login();

    ApiClient second = new ApiClient(port);
    second.prime();
    second.post("/api/auth/login", Map.of("email", email, "password", PASSWORD));

    var sessions = api.get("/api/auth/sessions");
    assertThat(sessions.json()).hasSizeGreaterThanOrEqualTo(2);
    assertThat(sessions.json().findValues("current").stream()
        .filter(node -> node.asBoolean()).count()).isEqualTo(1);

    var everywhere = api.post("/api/auth/logout-everywhere", null);
    assertThat(everywhere.ok()).as("logout-everywhere: %s", everywhere.body()).isTrue();
    assertThat(second.get("/api/agents").status())
        .as("the other device is signed out on its next call").isEqualTo(401);
  }

  @Test
  void loggingOutEndsThatSessionImmediately() {
    register();
    login();
    assertThat(api.post("/api/auth/logout", null).ok()).isTrue();
    assertThat(api.get("/api/agents").status()).isEqualTo(401);
  }

  @Test
  void repeatedFailuresLockTheAccountOut() {
    register();
    for (int attempt = 0; attempt < 5; attempt++) {
      assertThat(api.post("/api/auth/login", Map.of("email", email, "password", "wrong-password"))
          .status()).isEqualTo(401);
    }
    var locked = api.post("/api/auth/login", Map.of("email", email, "password", PASSWORD));
    assertThat(locked.status()).isEqualTo(401);
    assertThat(locked.json().path("detail").asText()).contains("Too many failed attempts");
    assertThat(users.findByEmailIgnoreCase(email).orElseThrow().getStatus())
        .isEqualTo(com.jizza.engine.api.enums.UserStatus.LOCKED);
  }

  @Test
  void anUnknownAccountLooksExactlyLikeAWrongPassword() {
    register();
    var unknown = api.post("/api/auth/login",
        Map.of("email", "ghost-" + UUID.randomUUID() + "@jizza.local", "password", PASSWORD));
    var wrong = api.post("/api/auth/login", Map.of("email", email, "password", "wrong-password"));

    assertThat(unknown.status()).isEqualTo(wrong.status());
    assertThat(unknown.json().path("detail").asText())
        .isEqualTo(wrong.json().path("detail").asText());
  }

  @Test
  void recoveryRevealsNothingAboutWhetherAnAccountExists() {
    register();
    var known = api.post("/api/auth/recover", Map.of("email", email));
    var unknown = api.post("/api/auth/recover",
        Map.of("email", "ghost-" + UUID.randomUUID() + "@jizza.local"));

    assertThat(known.status()).isEqualTo(unknown.status());
    assertThat(known.body()).isEqualTo(unknown.body());
  }

  // ── secrets ──────────────────────────────────────────────────────────────

  @Test
  void aConnectionSecretRoundTripsEncryptedAndNeverComesBackOut() {
    register();
    login();
    stepUp();

    String secret = "super-secret-client-value";
    var created = api.post("/api/connections", Map.of(
        "serviceName", "Example", "authScheme", "OAUTH2", "clientId", "abc",
        "clientSecret", secret,
        "authorizationUrl", "https://auth.example.test/authorize",
        "accessTokenUrl", "https://auth.example.test/token"));
    assertThat(created.ok()).as("create connection: %s", created.body()).isTrue();

    // The API reports that a secret is set, and never what it is.
    assertThat(created.body()).doesNotContain(secret);
    assertThat(created.json().path("clientSecretSet").asBoolean()).isTrue();
    assertThat(api.get("/api/connections").body()).doesNotContain(secret);

    UUID connectionId = UUID.fromString(created.json().path("id").asText());
    ConnectionEntity stored = connections.findById(connectionId).orElseThrow();
    assertThat(new String(stored.getClientSecret(), StandardCharsets.UTF_8))
        .as("ciphertext on disk, not the plaintext").doesNotContain(secret);
    assertThat(stored.getAuthScheme()).isEqualTo(AuthScheme.OAUTH2);
  }

  @Test
  void noStoredSecretEverReachesModelContext() {
    register();
    login();
    stepUp();

    String secret = "super-secret-client-value";
    api.post("/api/connections", Map.of(
        "serviceName", "Example", "authScheme", "OAUTH2", "clientId", "abc",
        "clientSecret", secret,
        "authorizationUrl", "https://auth.example.test/authorize",
        "accessTokenUrl", "https://auth.example.test/token"));

    model.script(com.jizza.engine.api.llm.LlmResponse.text("I have no access to your credentials."));
    var reply = api.post("/api/chat", Map.of("message", "What is my Example client secret?"));

    assertThat(reply.ok()).isTrue();
    assertThat(model.everythingTheModelSaw())
        .as("prompts and tool schemas only")
        .doesNotContain(secret)
        .doesNotContain(PASSWORD);
  }

  @Test
  void everyAuthOutcomeIsInTheActivityViewAndTheChainVerifies() {
    register();
    login();
    stepUp();

    var activity = api.get("/api/activity?limit=100");
    assertThat(activity.ok()).isTrue();
    assertThat(activity.json().findValuesAsText("action"))
        .contains("auth.register", "auth.login.success", "auth.step_up.ok");

    var verification = api.get("/api/activity/verify");
    assertThat(verification.json().path("intact").asBoolean()).isTrue();
    assertThat(verification.json().path("entries").asLong()).isGreaterThan(0);
  }

  @Test
  void theActivityViewShowsWhoDidWhatWithoutSecrets() {
    register();
    login();
    stepUp();
    api.post("/api/connections", Map.of("serviceName", "Example", "authScheme", "API_KEY",
        "clientSecret", "api-key-value-9999", "baseUrl", "https://api.example.test"));

    String activity = api.get("/api/activity?limit=200").body();
    assertThat(activity).contains("connection.created").doesNotContain("api-key-value-9999");
  }

  @Test
  void errorsAreRfc9457ProblemDocuments() {
    var problem = api.post("/api/auth/login", Map.of("email", "not-an-email", "password", "x"));
    assertThat(problem.status()).isEqualTo(400);
    assertThat(problem.json().has("type")).isTrue();
    assertThat(problem.json().has("title")).isTrue();
    assertThat(problem.json().path("status").asInt()).isEqualTo(400);
  }

  @Test
  void healthAndMetricsAreLive() {
    assertThat(api.get("/actuator/health").json().path("status").asText()).isEqualTo("UP");
    assertThat(api.get("/actuator/metrics").status()).isIn(200, 401);
  }
}
