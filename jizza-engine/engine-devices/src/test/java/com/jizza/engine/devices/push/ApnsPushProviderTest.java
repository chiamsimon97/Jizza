package com.jizza.engine.devices.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.util.Json;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Apple push against a stand-in for APNs. */
class ApnsPushProviderTest {

  private static final String TOKEN = "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90";

  private HttpServer server;
  private KeyPair keys;
  private String base;
  private final List<String> paths = new ArrayList<>();
  private final List<String> bodies = new ArrayList<>();
  private final List<Headers> headers = new ArrayList<>();
  private volatile int status = 200;
  private volatile String reply = "";

  @BeforeEach
  void start() throws Exception {
    KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
    gen.initialize(new ECGenParameterSpec("secp256r1"));
    keys = gen.generateKeyPair();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/3/device/", exchange -> {
      paths.add(exchange.getRequestURI().getPath());
      headers.add(exchange.getRequestHeaders());
      bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
      if (bytes.length > 0) exchange.getResponseBody().write(bytes);
      exchange.close();
    });
    server.start();
    base = "http://127.0.0.1:" + server.getAddress().getPort();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  @Test
  void inboxIsAVisibleAlertWithoutTheItemsText() {
    ApnsPushProvider apns = provider(Clock.systemUTC());
    boolean ok = apns.send(TOKEN, Json.obj().put("type", "inbox").put("notificationId", "n-1"));
    assertThat(ok).isTrue();
    assertThat(apns.name()).isEqualTo("apns");
    assertThat(paths.get(0)).isEqualTo("/3/device/" + TOKEN);
    Headers h = headers.get(0);
    assertThat(h.getFirst("apns-topic")).isEqualTo("com.jizza.ios");
    assertThat(h.getFirst("apns-push-type")).isEqualTo("alert");
    assertThat(h.getFirst("apns-priority")).isEqualTo("10");
    assertThat(h.getFirst("apns-collapse-id")).isEqualTo("inbox-n-1");
    JsonNode body = Json.read(bodies.get(0));
    assertThat(body.at("/aps/alert/title").asText()).isEqualTo("Jizza needs your answer");
    assertThat(body.at("/aps/category").asText()).isEqualTo("JIZZA_INBOX");
    assertThat(body.at("/jizza/type").asText()).isEqualTo("inbox");
    assertThat(body.at("/jizza/notificationId").asText()).isEqualTo("n-1");
  }

  @Test
  void aVitaReminderIsAFixedLineWithIdsOnly() {
    ApnsPushProvider apns = provider(Clock.systemUTC());
    apns.send(TOKEN, Json.obj().put("type", "vita_slot").put("personId", "p-1").put("dueAt", "2026-12-01T02:30:00Z"));
    apns.send(TOKEN, Json.obj().put("type", "vita_missed").put("personId", "p-1").put("dueAt", "2026-12-01T02:30:00Z"));
    JsonNode slot = Json.read(bodies.get(0));
    assertThat(slot.at("/aps/alert/title").asText()).isEqualTo("Medicines reminder");
    assertThat(slot.at("/aps/category").asText()).isEqualTo("JIZZA_VITA_SLOT");
    assertThat(slot.at("/aps/thread-id").asText()).isEqualTo("vita");
    assertThat(slot.at("/jizza/personId").asText()).isEqualTo("p-1");
    assertThat(headers.get(0).getFirst("apns-push-type")).isEqualTo("alert");
    assertThat(headers.get(0).getFirst("apns-collapse-id")).isEqualTo("vita_slot-p-1-1796092200");
    assertThat(Json.read(bodies.get(1)).at("/aps/category").asText()).isEqualTo("JIZZA_VITA_MISSED");
  }

  @Test
  void runFailedIsAnAlertAndDeviceWorkIsSilent() {
    ApnsPushProvider apns = provider(Clock.systemUTC());
    apns.send(TOKEN, Json.obj().put("type", "run_failed").put("runId", "r-9"));
    apns.send(TOKEN, Json.obj().put("type", "work").put("commandId", "c-1"));
    JsonNode failed = Json.read(bodies.get(0));
    assertThat(failed.at("/aps/alert/title").asText()).isEqualTo("A run failed");
    assertThat(failed.at("/jizza/runId").asText()).isEqualTo("r-9");
    assertThat(headers.get(0).getFirst("apns-collapse-id")).isEqualTo("run_failed-r-9");
    JsonNode work = Json.read(bodies.get(1));
    assertThat(work.at("/aps/content-available").asInt()).isEqualTo(1);
    assertThat(work.at("/aps/alert").isMissingNode()).isTrue();
    assertThat(headers.get(1).getFirst("apns-push-type")).isEqualTo("background");
    assertThat(headers.get(1).getFirst("apns-priority")).isEqualTo("5");
    assertThat(headers.get(1).getFirst("apns-collapse-id")).isNull();
  }

  @Test
  void signsAnEs256ProviderTokenAndReusesItForFiftyMinutes() throws Exception {
    MutableClock clock = new MutableClock(Instant.parse("2026-11-05T08:00:00Z"));
    ApnsPushProvider apns = provider(clock);
    apns.send(TOKEN, Json.obj().put("type", "work"));
    apns.send(TOKEN, Json.obj().put("type", "work"));
    clock.now = clock.now.plusSeconds(51 * 60);
    apns.send(TOKEN, Json.obj().put("type", "work"));
    String first = bearer(0);
    assertThat(bearer(1)).isEqualTo(first);
    assertThat(bearer(2)).isNotEqualTo(first);

    String[] parts = first.split("\\.");
    Base64.Decoder b64 = Base64.getUrlDecoder();
    JsonNode header = Json.read(new String(b64.decode(parts[0]), StandardCharsets.UTF_8));
    JsonNode claims = Json.read(new String(b64.decode(parts[1]), StandardCharsets.UTF_8));
    assertThat(header.path("alg").asText()).isEqualTo("ES256");
    assertThat(header.path("kid").asText()).isEqualTo("KEY1234567");
    assertThat(claims.path("iss").asText()).isEqualTo("TEAM123456");
    assertThat(claims.path("iat").asLong()).isEqualTo(Instant.parse("2026-11-05T08:00:00Z")
        .getEpochSecond());
    byte[] signature = b64.decode(parts[2]);
    assertThat(signature).hasSize(64);
    Signature verify = Signature.getInstance("SHA256withECDSAinP1363Format");
    verify.initVerify(keys.getPublic());
    verify.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
    assertThat(verify.verify(signature)).isTrue();
  }

  @Test
  void anExpiredProviderTokenIsRemadeOnce() {
    status = 403;
    reply = "{\"reason\":\"ExpiredProviderToken\"}";
    ApnsPushProvider apns = provider(Clock.systemUTC());
    assertThatThrownBy(() -> apns.send(TOKEN, Json.obj().put("type", "work")))
        .hasMessageContaining("403");
    assertThat(paths).hasSize(2);
  }

  @Test
  void forgetsTokensAppleNoLongerKnows() {
    ApnsPushProvider apns = provider(Clock.systemUTC());
    status = 410;
    reply = "{\"reason\":\"Unregistered\"}";
    assertThat(apns.send(TOKEN, Json.obj().put("type", "inbox"))).isFalse();
    status = 400;
    reply = "{\"reason\":\"BadDeviceToken\"}";
    assertThat(apns.send(TOKEN, Json.obj().put("type", "inbox"))).isFalse();
    status = 400;
    reply = "{\"reason\":\"DeviceTokenNotForTopic\"}";
    assertThat(apns.send(TOKEN, Json.obj().put("type", "inbox"))).isFalse();
    assertThat(apns.send("not-a-token", Json.obj().put("type", "inbox"))).isFalse();
    assertThat(paths).hasSize(3);
  }

  @Test
  void otherFailuresThrowSoTheDispatcherLogsThem() {
    status = 500;
    reply = "{\"reason\":\"InternalServerError\"}";
    ApnsPushProvider apns = provider(Clock.systemUTC());
    assertThatThrownBy(() -> apns.send(TOKEN, Json.obj().put("type", "inbox")))
        .hasMessageContaining("500");
  }

  @Test
  void readsTheP8KeyFileAndConfigTurnsOnOnlyWhenComplete(@TempDir Path dir) throws Exception {
    String pem = "-----BEGIN PRIVATE KEY-----\n"
        + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
            .encodeToString(keys.getPrivate().getEncoded())
        + "\n-----END PRIVATE KEY-----\n";
    Path file = dir.resolve("AuthKey_KEY1234567.p8");
    Files.writeString(file, pem);
    assertThat(ApnsPushProvider.readKey(pem).getAlgorithm()).isEqualTo("EC");

    ApnsPushConfig config = new ApnsPushConfig();
    assertThat(config.apnsPushProvider(file.toString(), "KEY1234567", "TEAM123456",
        "com.jizza.ios", true).name()).isEqualTo("apns");
    assertThat(config.apnsPushProvider("", "", "", "", false).name()).isEqualTo("none-apns");
    assertThat(config.apnsPushProvider(file.toString(), "", "TEAM123456", "com.jizza.ios",
        false).name()).isEqualTo("none-apns");
    assertThat(config.apnsPushProvider(dir.resolve("missing.p8").toString(), "KEY1234567",
        "TEAM123456", "com.jizza.ios", false).name()).isEqualTo("none-apns");
    Path junk = dir.resolve("junk.p8");
    Files.writeString(junk, "not a key");
    assertThat(config.apnsPushProvider(junk.toString(), "KEY1234567", "TEAM123456",
        "com.jizza.ios", false).name()).isEqualTo("none-apns");
  }

  private String bearer(int index) {
    String value = headers.get(index).getFirst("authorization");
    assertThat(value).startsWith("bearer ");
    return value.substring(7);
  }

  private ApnsPushProvider provider(Clock clock) {
    return new ApnsPushProvider(new ApnsPushProvider.Settings("KEY1234567", "TEAM123456",
        "com.jizza.ios", keys.getPrivate()), base, HttpClient.newHttpClient(), clock);
  }

  private static final class MutableClock extends Clock {
    Instant now;

    MutableClock(Instant now) {
      this.now = now;
    }

    @Override
    public java.time.ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
