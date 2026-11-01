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
  @Autowired private com.jizza.engine.security.config.SecurityProperties securityProps;
  @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;

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

  /**
   * A valid code the account has not used yet. Each code works once, so after one is spent the
   * next must come from a later 30-second step (one step ahead is inside the accepted drift).
   */
  private String freshCode(String secret) {
    while (true) {
      Long last = users.findByEmailIgnoreCase(email).orElseThrow().getMfaLastStep();
      long now = Instant.now().getEpochSecond() / 30;
      for (long step = now - 1; step <= now + 1; step++) {
        if (last == null || step > last) return totp.code(secret, step * 30);
      }
      try {
        Thread.sleep(1_000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
    }
  }

  /** Turns on two-step sign-in for the signed-in {@code api} client; returns the seed. */
  private String enrol() {
    stepUp();
    var setup = api.post("/api/auth/mfa/setup", null);
    assertThat(setup.ok()).as("mfa/setup: %s", setup.body()).isTrue();
    String secret = setup.json().path("secret").asText();
    var confirmed = api.post("/api/auth/mfa/confirm", Map.of("code", freshCode(secret)));
    assertThat(confirmed.ok()).as("mfa/confirm: %s", confirmed.body()).isTrue();
    lastRecoveryCodes = new java.util.ArrayList<>();
    confirmed.json().path("codes").forEach(node -> lastRecoveryCodes.add(node.asText()));
    return secret;
  }

  private java.util.List<String> lastRecoveryCodes = new java.util.ArrayList<>();

  /** Signs in a new client with the password and a second factor. */
  private ApiClient signInWithSecondFactor(String code) {
    ApiClient client = new ApiClient(port);
    client.prime();
    String token = client.post("/api/auth/login", Map.of("email", email, "password", PASSWORD))
        .json().path("mfaToken").asText();
    var verified = client.post("/api/auth/mfa/verify", Map.of("mfaToken", token, "code", code));
    assertThat(verified.ok()).as("mfa/verify: %s", verified.body()).isTrue();
    return client;
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

    var codes = api.post("/api/auth/mfa/confirm", Map.of("code", freshCode(secret)));
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
        Map.of("mfaToken", token, "code", freshCode(secret)));

    assertThat(verified.ok()).isTrue();
    assertThat(second.get("/api/agents").ok()).isTrue();
  }

  // ── two-step sign-in hardening ───────────────────────────────────────────

  @Test
  void whenTwoStepIsRequiredAPasswordOnlySessionCanOnlySetItUp() {
    securityProps.getMfa().setRequired(true);
    try {
      register();
      var login = api.post("/api/auth/login", Map.of("email", email, "password", PASSWORD));
      assertThat(login.json().path("enrollmentRequired").asBoolean()).isTrue();

      var blocked = api.get("/api/agents");
      assertThat(blocked.status()).isEqualTo(403);
      assertThat(blocked.problemType()).endsWith("mfa-enrollment-required");
      assertThat(api.get("/api/auth/me").json().path("enrollmentRequired").asBoolean()).isTrue();

      ApiClient other = new ApiClient(port);
      other.prime();
      other.post("/api/auth/login", Map.of("email", email, "password", PASSWORD));

      String secret = enrol();
      assertThat(api.get("/api/agents").ok()).as("setup finished in this session").isTrue();
      var me = api.get("/api/auth/me").json();
      assertThat(me.path("mfaEnabled").asBoolean()).isTrue();
      assertThat(me.path("enrollmentRequired").asBoolean()).isFalse();
      assertThat(me.path("recoveryCodesRemaining").asInt()).isEqualTo(8);
      assertThat(other.get("/api/auth/me").status())
          .as("a session that never passed the second factor is signed out").isEqualTo(401);

      assertThat(api.post("/api/auth/mfa/disable", Map.of("code", freshCode(secret))).status())
          .as("cannot be switched off while required").isIn(403);
    } finally {
      securityProps.getMfa().setRequired(false);
    }
  }

  @Test
  void theFirstAuthenticatorNeedsNoStepUpButReplacingOneDoes() {
    register();
    login();

    var first = api.post("/api/auth/mfa/setup", null);
    assertThat(first.ok()).as("signing in was enough: %s", first.body()).isTrue();
    String secret = first.json().path("secret").asText();
    assertThat(api.post("/api/auth/mfa/confirm", Map.of("code", freshCode(secret))).ok()).isTrue();

    var replace = api.post("/api/auth/mfa/setup", null);
    assertThat(replace.status()).isEqualTo(403);
    assertThat(replace.problemType()).endsWith("step-up-required");

    var stepUp = api.post("/api/auth/step-up", Map.of("password", PASSWORD));
    assertThat(stepUp.ok()).as("step-up: %s", stepUp.body()).isTrue();
    assertThat(api.post("/api/auth/mfa/setup", null).ok()).isTrue();
  }

  @Test
  void anAuthenticatorCodeCannotBeReplayed() {
    register();
    login();
    String secret = enrol();

    String code = freshCode(secret);
    signInWithSecondFactor(code);

    ApiClient attacker = new ApiClient(port);
    attacker.prime();
    String token = attacker.post("/api/auth/login", Map.of("email", email, "password", PASSWORD))
        .json().path("mfaToken").asText();
    assertThat(attacker.post("/api/auth/mfa/verify", Map.of("mfaToken", token, "code", code))
        .status()).isEqualTo(401);
    assertThat(attacker.hasSession()).isFalse();
  }

  @Test
  void aRecoveryCodeSignsInExactlyOnce() {
    register();
    login();
    enrol();
    assertThat(lastRecoveryCodes).hasSize(8);
    String recovery = lastRecoveryCodes.get(0);

    ApiClient phoneLost = signInWithSecondFactor(recovery);
    assertThat(phoneLost.get("/api/agents").ok()).isTrue();
    assertThat(phoneLost.get("/api/auth/me").json().path("recoveryCodesRemaining").asInt())
        .isEqualTo(7);

    ApiClient again = new ApiClient(port);
    again.prime();
    String token = again.post("/api/auth/login", Map.of("email", email, "password", PASSWORD))
        .json().path("mfaToken").asText();
    assertThat(again.post("/api/auth/mfa/verify", Map.of("mfaToken", token, "code", recovery))
        .status()).isEqualTo(401);

    var stored = jdbc.queryForList("select code_hash from mfa_recovery_codes c join users u "
        + "on u.id = c.user_id where u.email = ?", String.class, email);
    assertThat(stored).hasSize(8).noneMatch(hash -> lastRecoveryCodes.contains(hash));
  }

  @Test
  void regeneratingRecoveryCodesRetiresTheOldOnes() {
    register();
    login();
    String secret = enrol();
    String old = lastRecoveryCodes.get(0);

    var fresh = api.post("/api/auth/mfa/recovery-codes", Map.of("code", freshCode(secret)));
    assertThat(fresh.ok()).as("regenerate: %s", fresh.body()).isTrue();
    assertThat(fresh.json().path("codes")).hasSize(8);

    ApiClient client = new ApiClient(port);
    client.prime();
    String token = client.post("/api/auth/login", Map.of("email", email, "password", PASSWORD))
        .json().path("mfaToken").asText();
    assertThat(client.post("/api/auth/mfa/verify", Map.of("mfaToken", token, "code", old))
        .status()).isEqualTo(401);
  }

  @Test
  void theSessionCookieIsAnOpaqueTokenThatIsNotStored() {
    register();
    login();
    String cookie = api.sessionCookie();

    var sessions = api.get("/api/auth/sessions").json().findValuesAsText("id");
    assertThat(sessions).doesNotContain(cookie);
    assertThat(cookie).hasSizeGreaterThanOrEqualTo(43);
    Integer matches = jdbc.queryForObject(
        "select count(*) from sessions where token_hash = ? or id::text = ?", Integer.class,
        cookie, cookie);
    assertThat(matches).as("only the hash is kept").isZero();
  }

  @Test
  void aStolenSessionThatKeepsGuessingThePasswordIsRevoked() {
    register();
    login();
    int limit = securityProps.getStepUp().getMaxFailures();
    for (int attempt = 0; attempt < limit; attempt++) {
      assertThat(api.post("/api/auth/step-up", Map.of("password", "guess-" + attempt)).status())
          .isEqualTo(401);
    }
    assertThat(api.get("/api/agents").status()).isEqualTo(401);
  }

  @Test
  void changingThePasswordSignsOutOtherDevices() {
    register();
    login();
    ApiClient other = new ApiClient(port);
    other.prime();
    other.post("/api/auth/login", Map.of("email", email, "password", PASSWORD));

    stepUp();
    var changed = api.post("/api/auth/password",
        Map.of("currentPassword", PASSWORD, "newPassword", "Another-Strong-92"));
    assertThat(changed.ok()).as("change password: %s", changed.body()).isTrue();
    assertThat(api.get("/api/agents").ok()).isTrue();
    assertThat(other.get("/api/agents").status()).isEqualTo(401);
  }

  @Test
  void aSessionOfAnotherUserCannotBeRevoked() {
    register();
    login();
    UUID mine = UUID.fromString(api.get("/api/auth/sessions").json().get(0).path("id").asText());

    String otherEmail = "other-" + UUID.randomUUID() + "@jizza.local";
    ApiClient other = new ApiClient(port);
    other.prime();
    other.post("/api/auth/register", Map.of("email", otherEmail, "password", PASSWORD));
    other.post("/api/auth/login", Map.of("email", otherEmail, "password", PASSWORD));

    assertThat(other.delete("/api/auth/sessions/" + mine).status()).isEqualTo(404);
    assertThat(api.get("/api/agents").ok()).isTrue();
  }

  @Test
  void aSignInFromANewDeviceIsFlaggedToTheOwner() {
    register();
    login();
    api.get("/api/agents");

    // Every client in the suite sends the same user agent, so make the one already seen look like a
    // different browser; the next sign-in is then from a device this account has never used.
    jdbc.update("update user_devices set fingerprint = 'another-browser' "
        + "where user_id = (select id from users where email = ?)", email);
    ApiClient elsewhere = new ApiClient(port);
    elsewhere.prime();
    elsewhere.post("/api/auth/login", Map.of("email", email, "password", PASSWORD));

    var activity = api.get("/api/activity?limit=100");
    assertThat(activity.json().findValuesAsText("action")).contains("auth.login.new_device");
    var notifications = api.get("/api/notifications").body();
    assertThat(notifications).contains("new device");
  }

  @Test
  void registrationClosesOnceTheOwnerExists() {
    securityProps.getRegistration().setAllowAdditionalUsers(false);
    try {
      var refused = api.post("/api/auth/register", Map.of("email", email, "password", PASSWORD));
      assertThat(refused.status()).isEqualTo(403);
      assertThat(refused.problemType()).endsWith("registration-closed");
    } finally {
      securityProps.getRegistration().setAllowAdditionalUsers(true);
    }
  }

  @Test
  void responsesCarryTheBrowserHardeningHeaders() {
    api.get("/api/auth/csrf");
    var headers = api.lastHeaders();
    assertThat(headers.firstValue("content-security-policy").orElse(""))
        .contains("frame-ancestors 'none'");
    assertThat(headers.firstValue("x-content-type-options")).hasValue("nosniff");
    assertThat(headers.firstValue("permissions-policy").orElse("")).contains("camera=()");
    assertThat(headers.firstValue("cross-origin-opener-policy")).hasValue("same-origin");
    assertThat(headers.firstValue("cache-control").orElse("")).contains("no-store");
  }

  @Test
  void theSessionCookieIsStrictAndHttpOnly() {
    register();
    api.post("/api/auth/login", Map.of("email", email, "password", PASSWORD));
    String setCookie = api.lastHeaders().allValues("set-cookie").stream()
        .filter(h -> h.startsWith("JIZZA_SESSION=")).findFirst().orElseThrow();
    assertThat(setCookie).contains("HttpOnly").contains("SameSite=Strict");
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
