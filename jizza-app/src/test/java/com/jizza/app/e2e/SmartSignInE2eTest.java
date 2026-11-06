package com.jizza.app.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Acceptance: smart sign-in. A browser on the login page gets a QR code, a phone already signed in
 * scans and approves it, and the browser collects a session it can use.
 */
@org.junit.jupiter.api.condition.EnabledIf(
    value = "com.jizza.engine.persistence.support.TestDatabase#available",
    disabledReason = "no PostgreSQL: set JIZZA_TEST_DB_URL or start Docker")
class SmartSignInE2eTest extends E2eSupport {

  private static final String PASSWORD = "Correct-Horse-92";
  private static final String POLL = "X-Smart-Sign-In-Token";

  @LocalServerPort private int port;

  private ApiClient browser;
  private ApiClient phone;
  private String email;

  @BeforeEach
  void setUp() {
    browser = new ApiClient(port);
    browser.prime();
    phone = new ApiClient(port);
    phone.prime();
    email = "smart-" + UUID.randomUUID() + "@jizza.local";
    assertThat(phone.post("/api/auth/register", Map.of("email", email, "password", PASSWORD)).ok())
        .isTrue();
    assertThat(phone.post("/api/auth/login", Map.of("email", email, "password", PASSWORD)).ok())
        .isTrue();
  }

  @Test
  void aPhoneThatIsSignedInSignsTheBrowserIn() {
    assertThat(browser.get("/api/auth/smart-sign-in/config").json().path("enabled").asBoolean())
        .isTrue();
    var started = browser.post("/api/auth/smart-sign-in/start", null);
    assertThat(started.ok()).as("start: %s", started.body()).isTrue();
    String id = started.json().path("id").asText();
    String code = started.json().path("code").asText();
    String poll = started.json().path("pollToken").asText();

    assertThat(status(id, poll)).isEqualTo("PENDING");

    var scanned = phone.post("/api/auth/smart-sign-in/scan", Map.of("code", code));
    assertThat(scanned.ok()).as("scan: %s", scanned.body()).isTrue();
    assertThat(scanned.json().path("ip").asText()).isNotBlank();
    assertThat(status(id, poll)).isEqualTo("SCANNED");

    var decided = phone.post("/api/auth/smart-sign-in/decide", Map.of("code", code, "approve", true));
    assertThat(decided.ok()).as("decide: %s", decided.body()).isTrue();
    assertThat(status(id, poll)).isEqualTo("APPROVED");

    assertThat(browser.hasSession()).isFalse();
    var complete = browser.post("/api/auth/smart-sign-in/attempts/" + id + "/complete",
        Map.of("pollToken", poll));
    assertThat(complete.ok()).as("complete: %s", complete.body()).isTrue();
    assertThat(browser.hasSession()).isTrue();
    assertThat(browser.get("/api/auth/me").ok()).isTrue();

    // Works once.
    var again = browser.post("/api/auth/smart-sign-in/attempts/" + id + "/complete",
        Map.of("pollToken", poll));
    assertThat(again.status()).isEqualTo(404);
  }

  @Test
  void theCodeAloneCannotCollectTheSession() {
    var started = browser.post("/api/auth/smart-sign-in/start", null).json();
    String id = started.path("id").asText();
    String code = started.path("code").asText();
    phone.post("/api/auth/smart-sign-in/decide", Map.of("code", code, "approve", true));

    ApiClient attacker = new ApiClient(port);
    attacker.prime();
    var stolen = attacker.post("/api/auth/smart-sign-in/attempts/" + id + "/complete",
        Map.of("pollToken", code));
    assertThat(stolen.status()).isEqualTo(404);
    assertThat(attacker.hasSession()).isFalse();
  }

  @Test
  void approvingNeedsASignedInPhone() {
    String code = browser.post("/api/auth/smart-sign-in/start", null).json().path("code").asText();
    ApiClient stranger = new ApiClient(port);
    stranger.prime();
    assertThat(stranger.post("/api/auth/smart-sign-in/decide", Map.of("code", code, "approve", true))
        .status()).isEqualTo(401);
  }

  @Test
  void aDeniedCodeSignsNobodyIn() {
    var started = browser.post("/api/auth/smart-sign-in/start", null).json();
    String id = started.path("id").asText();
    String poll = started.path("pollToken").asText();
    phone.post("/api/auth/smart-sign-in/decide",
        Map.of("code", started.path("code").asText(), "approve", false));
    assertThat(status(id, poll)).isEqualTo("DENIED");
    assertThat(browser.post("/api/auth/smart-sign-in/attempts/" + id + "/complete",
        Map.of("pollToken", poll)).ok()).isFalse();
    assertThat(browser.hasSession()).isFalse();
  }

  @Test
  void aPairedPhoneApprovesWithASignatureFromItsKey() throws Exception {
    assertThat(phone.post("/api/auth/step-up", Map.of("password", PASSWORD)).ok()).isTrue();
    var pairing = phone.post("/api/devices/pairings", Map.of());
    var device = new com.jizza.devicesim.SimDevice("http://localhost:" + port, true);
    try {
      device.enrollWithCode(pairing.json().path("code").asText(), "Chiam's Pixel", "android");
      device.signIn();

      var started = browser.post("/api/auth/smart-sign-in/start", null).json();
      String id = started.path("id").asText();
      String code = started.path("code").asText();
      String poll = started.path("pollToken").asText();

      var scanned = agent("/agent/v1/smart-sign-in/scan", device.token(),
          Map.of("code", code));
      assertThat(scanned.statusCode()).as(scanned.body()).isEqualTo(200);

      // A signature over the other answer, or by no key, is refused.
      var forged = agent("/agent/v1/smart-sign-in/decide", device.token(), Map.of("code", code,
          "approve", true, "signature", device.sign("jizza-smart-sign-in:v1:" + code + ":deny")));
      assertThat(forged.statusCode()).isEqualTo(401);
      assertThat(status(id, poll)).isEqualTo("SCANNED");

      var approved = agent("/agent/v1/smart-sign-in/decide", device.token(), Map.of("code", code,
          "approve", true, "signature", device.sign("jizza-smart-sign-in:v1:" + code + ":approve")));
      assertThat(approved.statusCode()).as(approved.body()).isEqualTo(200);
      assertThat(status(id, poll)).isEqualTo("APPROVED");

      assertThat(browser.post("/api/auth/smart-sign-in/attempts/" + id + "/complete",
          Map.of("pollToken", poll)).ok()).isTrue();
      assertThat(browser.get("/api/auth/me").ok()).isTrue();
    } finally {
      device.close();
    }
  }

  private java.net.http.HttpResponse<String> agent(String path, String token,
                                                   Map<String, Object> body)
      throws Exception {
    var request = java.net.http.HttpRequest.newBuilder(
            java.net.URI.create("http://localhost:" + port + path))
        .header("Authorization", "Bearer " + token)
        .header("content-type", "application/json")
        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(
            com.jizza.engine.api.util.Json.write(body)))
        .build();
    return java.net.http.HttpClient.newHttpClient().send(request,
        java.net.http.HttpResponse.BodyHandlers.ofString());
  }

  private String status(String id, String poll) {
    var result = browser.sendWithHeaders("GET", "/api/auth/smart-sign-in/attempts/" + id, null,
        Map.of(POLL, poll));
    assertThat(result.ok()).as("status: %s", result.body()).isTrue();
    return result.json().path("state").asText();
  }
}
