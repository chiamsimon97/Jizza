package com.jizza.engine.devices.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.util.Json;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Firebase push against a stand-in for Google's token and FCM endpoints. */
class FcmPushProviderTest {

  private HttpServer server;
  private KeyPair keys;
  private String base;
  private final List<String> sent = new ArrayList<>();
  private final List<String> authHeaders = new ArrayList<>();
  private final AtomicInteger tokenCalls = new AtomicInteger();
  private volatile int sendStatus = 200;
  private volatile String sendBody = "{\"name\":\"projects/p/messages/1\"}";

  @BeforeEach
  void start() throws Exception {
    KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
    gen.initialize(2048);
    keys = gen.generateKeyPair();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/token", exchange -> {
      String form = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      tokenCalls.incrementAndGet();
      boolean ok = form.contains("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer")
          && verifies(java.net.URLDecoder.decode(form.substring(form.indexOf("assertion=") + 10),
              StandardCharsets.UTF_8));
      reply(exchange, ok ? 200 : 400, ok
          ? "{\"access_token\":\"ya29.test-" + tokenCalls.get() + "\",\"expires_in\":3600}"
          : "{\"error\":\"invalid_grant\"}");
    });
    server.createContext("/v1/projects/jizza-test/messages:send", exchange -> {
      authHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
      sent.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      reply(exchange, sendStatus, sendBody);
    });
    server.start();
    base = "http://127.0.0.1:" + server.getAddress().getPort();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  @Test
  void sendsAHighPriorityDataMessageWithStringValues() {
    FcmPushProvider fcm = provider();
    boolean ok = fcm.send("phone-token", Json.obj().put("type", "inbox")
        .put("notificationId", "n-1").put("count", 2));
    assertThat(ok).isTrue();
    assertThat(fcm.name()).isEqualTo("fcm");
    JsonNode message = Json.read(sent.get(0)).path("message");
    assertThat(message.path("token").asText()).isEqualTo("phone-token");
    assertThat(message.path("data").path("type").asText()).isEqualTo("inbox");
    assertThat(message.path("data").path("count").isTextual()).isTrue();
    assertThat(message.path("android").path("priority").asText()).isEqualTo("HIGH");
    assertThat(authHeaders.get(0)).isEqualTo("Bearer ya29.test-1");
  }

  @Test
  void keepsTheAccessTokenBetweenPushes() {
    FcmPushProvider fcm = provider();
    fcm.send("a", Json.obj().put("type", "command"));
    fcm.send("b", Json.obj().put("type", "command"));
    assertThat(tokenCalls.get()).isEqualTo(1);
  }

  @Test
  void anUnregisteredTokenIsReportedSoItIsForgotten() {
    sendStatus = 404;
    sendBody = "{\"error\":{\"status\":\"NOT_FOUND\",\"details\":[{\"errorCode\":\"UNREGISTERED\"}]}}";
    assertThat(provider().send("gone", Json.obj().put("type", "inbox"))).isFalse();
  }

  @Test
  void otherFailuresAreErrorsNotForgottenTokens() {
    sendStatus = 503;
    sendBody = "{\"error\":{\"status\":\"UNAVAILABLE\"}}";
    assertThatThrownBy(() -> provider().send("t", Json.obj())).hasMessageContaining("503");
  }

  @Test
  void readsAServiceAccountKeyFile(@TempDir Path dir) throws IOException {
    String pem = "-----BEGIN PRIVATE KEY-----\n"
        + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
            .encodeToString(keys.getPrivate().getEncoded())
        + "\n-----END PRIVATE KEY-----\n";
    Path file = dir.resolve("key.json");
    Files.writeString(file, Json.write(Json.obj().put("type", "service_account")
        .put("project_id", "jizza-test").put("client_email", "push@jizza-test.iam.gserviceaccount.com")
        .put("private_key", pem).put("token_uri", base + "/token")));
    var account = FcmPushProvider.read(Json.read(Files.readString(file)));
    assertThat(account.projectId()).isEqualTo("jizza-test");
    assertThat(account.tokenUri()).isEqualTo(base + "/token");

    assertThat(new FcmPushConfig().fcmPushProvider(file.toString()).name()).isEqualTo("fcm");
    assertThat(new FcmPushConfig().fcmPushProvider("").name()).isEqualTo("none");
    assertThat(new FcmPushConfig().fcmPushProvider(dir.resolve("missing.json").toString()).name())
        .isEqualTo("none");
  }

  @Test
  void refusesAFileThatIsNotAServiceAccountKey() {
    assertThatThrownBy(() -> FcmPushProvider.read(Json.obj().put("project_id", "x")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private FcmPushProvider provider() {
    var account = new FcmPushProvider.ServiceAccount("jizza-test",
        "push@jizza-test.iam.gserviceaccount.com", keys.getPrivate(), base + "/token");
    return new FcmPushProvider(account, base, HttpClient.newHttpClient(),
        Clock.fixed(Instant.parse("2026-11-03T12:00:00Z"), ZoneOffset.UTC));
  }

  private boolean verifies(String jwt) {
    try {
      String[] parts = jwt.split("\\.");
      Signature rsa = Signature.getInstance("SHA256withRSA");
      rsa.initVerify(keys.getPublic());
      rsa.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
      JsonNode claims = Json.read(new String(Base64.getUrlDecoder().decode(parts[1]),
          StandardCharsets.UTF_8));
      return rsa.verify(Base64.getUrlDecoder().decode(parts[2]))
          && FcmPushProvider.SCOPE.equals(claims.path("scope").asText())
          && claims.path("aud").asText().endsWith("/token");
    } catch (Exception e) {
      return false;
    }
  }

  private static void reply(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
      throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }
}
