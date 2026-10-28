package com.jizza.app.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
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

  @BeforeEach
  void setUp() {
    api = signedIn("web");
    device = new SimDevice("http://localhost:" + port);
    new FakeBrowser(device).page("https://example.com").title("Example");
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
    assertThat(result.path("status").asText()).isEqualTo("done");
    assertThat(result.path("element").path("strategies")).isNotEmpty();
    assertThat(result.path("url").asText()).isEqualTo("https://example.com");

    assertThat(signedIn("web-other").get("/api/web/pick/" + commandId).status()).isEqualTo(404);
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
