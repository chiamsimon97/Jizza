package com.jizza.engine.devices.push;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.device.PushProvider;
import com.jizza.engine.api.util.Json;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wakes iPhones through Apple's push service (APNs), with token-based sign-in: a short JWT signed
 * (ES256) with the team's APNs auth key (.p8 file), reused for up to 50 minutes as Apple asks.
 *
 * <p>A push still carries only the ids {@link PushDispatcher} gives it, under {@code jizza}. What
 * the person sees depends on its type, and never includes the item's own text, so nothing private
 * passes through Apple:
 *
 * <ul>
 *   <li>{@code inbox}: a visible alert "Jizza needs your answer", collapsed per notification;
 *   <li>{@code run_failed}: a visible alert "A run failed", collapsed per run;
 *   <li>{@code vita_slot}, {@code vita_missed}, {@code vita_daily_check}: a visible alert with a
 *       fixed line (categories JIZZA_VITA_SLOT, JIZZA_VITA_MISSED, JIZZA_VITA_DAILY, thread "vita"),
 *       collapsed per person and slot; no name, medicine or value;
 *   <li>anything else (device work): a silent background push ({@code content-available}).
 * </ul>
 *
 * <p>The app then fetches the details over HTTPS. Only {@link ApnsPushConfig} creates it.
 */
public class ApnsPushProvider implements PushProvider {

  private static final Logger log = LoggerFactory.getLogger(ApnsPushProvider.class);
  static final String PRODUCTION = "https://api.push.apple.com";
  static final String SANDBOX = "https://api.sandbox.push.apple.com";
  /** Apple refuses a provider token older than an hour and throttles fresher than 20 minutes. */
  static final Duration TOKEN_LIFE = Duration.ofMinutes(50);

  /** What the provider needs to sign in to APNs and address the app. */
  record Settings(String keyId, String teamId, String topic, PrivateKey key) {}

  private final Settings settings;
  private final String base;
  private final HttpClient http;
  private final Clock clock;

  private String providerToken;
  private Instant providerTokenMade = Instant.EPOCH;

  ApnsPushProvider(Settings settings, String base, HttpClient http, Clock clock) {
    this.settings = settings;
    this.base = base.replaceAll("/+$", "");
    this.http = http;
    this.clock = clock;
  }

  static ApnsPushProvider create(Path keyFile, String keyId, String teamId, String topic,
                                 boolean sandbox) throws IOException {
    PrivateKey key = readKey(Files.readString(keyFile));
    HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_2)
        .connectTimeout(Duration.ofSeconds(10)).build();
    return new ApnsPushProvider(new Settings(keyId.trim(), teamId.trim(), topic.trim(), key),
        sandbox ? SANDBOX : PRODUCTION, http, Clock.systemUTC());
  }

  @Override
  public String name() {
    return "apns";
  }

  @Override
  public boolean send(String token, JsonNode data) {
    if (token == null || !token.matches("[0-9a-fA-F]{16,200}")) {
      // Not an APNs device token at all, so Apple could never deliver to it.
      return false;
    }
    String type = data == null ? "" : data.path("type").asText("");
    ObjectNode payload = payload(type, data);
    boolean alert = isAlert(type);
    HttpResponse<String> response = post(token, payload, alert, collapseId(type, data), false);
    if (response.statusCode() == 403 && response.body() != null
        && response.body().contains("ExpiredProviderToken")) {
      response = post(token, payload, alert, collapseId(type, data), true);
    }
    int status = response.statusCode();
    if (status >= 200 && status < 300) return true;
    if (tokenGone(status, response.body())) return false;
    throw new IllegalStateException("APNs answered " + status + ": " + trim(response.body()));
  }

  static boolean isAlert(String type) {
    return type.equals("inbox") || type.equals("run_failed") || type.startsWith("vita_");
  }

  /** The APNs JSON: an alert or a silent wake, with the ids under {@code jizza}. */
  static ObjectNode payload(String type, JsonNode data) {
    ObjectNode body = Json.obj();
    ObjectNode aps = body.putObject("aps");
    switch (type) {
      case "inbox" -> {
        aps.putObject("alert").put("title", "Jizza needs your answer")
            .put("body", "Open Jizza to see what is waiting.");
        aps.put("sound", "default");
        aps.put("category", "JIZZA_INBOX");
        aps.put("thread-id", "inbox");
        aps.put("mutable-content", 1);
      }
      case "run_failed" -> {
        aps.putObject("alert").put("title", "A run failed")
            .put("body", "Open Jizza to see which step stopped.");
        aps.put("sound", "default");
        aps.put("category", "JIZZA_RUN_FAILED");
        aps.put("thread-id", "runs");
        aps.put("mutable-content", 1);
      }
      // Vita: a fixed line only. The app fetches the slot and never shows health text from a push.
      case "vita_slot", "vita_missed", "vita_daily_check" -> {
        String title = switch (type) {
          case "vita_slot" -> "Medicines reminder";
          case "vita_missed" -> "Medicines not marked";
          default -> "Medicines check";
        };
        aps.putObject("alert").put("title", title).put("body", "Open Jizza to see the list.");
        aps.put("sound", "default");
        aps.put("category", switch (type) {
          case "vita_slot" -> "JIZZA_VITA_SLOT";
          case "vita_missed" -> "JIZZA_VITA_MISSED";
          default -> "JIZZA_VITA_DAILY";
        });
        aps.put("thread-id", "vita");
        aps.put("mutable-content", 1);
      }
      default -> aps.put("content-available", 1);
    }
    ObjectNode ids = body.putObject("jizza");
    if (data != null && data.isObject()) {
      for (Iterator<Map.Entry<String, JsonNode>> it = data.fields(); it.hasNext(); ) {
        var e = it.next();
        ids.put(e.getKey(), e.getValue().isTextual() ? e.getValue().asText()
            : Json.write(e.getValue()));
      }
    }
    return body;
  }

  /** One alert per inbox item or run, so a repeat replaces it rather than stacking. */
  static String collapseId(String type, JsonNode data) {
    if (data == null) return null;
    if (type.startsWith("vita_")) {
      String person = data.path("personId").asText("");
      String due = data.path("dueAt").asText("");
      String when = "";
      try {
        if (!due.isEmpty()) when = "-" + java.time.Instant.parse(due).getEpochSecond();
      } catch (RuntimeException ignored) {
        // no time: one per person
      }
      String c = type + "-" + person + when;
      return c.length() <= 64 ? c : c.substring(0, 64);
    }
    String id = switch (type) {
      case "inbox" -> data.path("notificationId").asText(null);
      case "run_failed" -> data.path("runId").asText(null);
      default -> null;
    };
    if (id == null) return null;
    String collapse = type + "-" + id;
    return collapse.length() <= 64 ? collapse : collapse.substring(0, 64);
  }

  private HttpResponse<String> post(String token, ObjectNode payload, boolean alert,
                                    String collapseId, boolean freshToken) {
    HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + "/3/device/" + token))
        .timeout(Duration.ofSeconds(15))
        .header("authorization", "bearer " + providerToken(freshToken))
        .header("apns-topic", settings.topic())
        .header("apns-push-type", alert ? "alert" : "background")
        .header("apns-priority", alert ? "10" : "5")
        .header("apns-expiration", String.valueOf(clock.instant().plusSeconds(3600)
            .getEpochSecond()))
        .header("apns-id", UUID.randomUUID().toString())
        .header("content-type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(Json.write(payload)));
    if (collapseId != null) request.header("apns-collapse-id", collapseId);
    try {
      return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new IllegalStateException("Could not reach APNs", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while pushing", e);
    }
  }

  /** 410 Unregistered, or a 400 for a bad or wrong-environment token, means forget the token. */
  static boolean tokenGone(int status, String body) {
    if (status == 410) return true;
    if (status != 400 || body == null) return false;
    return body.contains("BadDeviceToken") || body.contains("DeviceTokenNotForTopic");
  }

  synchronized String providerToken(boolean fresh) {
    Instant now = clock.instant();
    if (!fresh && providerToken != null && now.isBefore(providerTokenMade.plus(TOKEN_LIFE))) {
      return providerToken;
    }
    Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
    String header = b64.encodeToString(Json.write(Json.obj().put("alg", "ES256")
        .put("kid", settings.keyId())).getBytes(StandardCharsets.UTF_8));
    String claims = b64.encodeToString(Json.write(Json.obj().put("iss", settings.teamId())
        .put("iat", now.getEpochSecond())).getBytes(StandardCharsets.UTF_8));
    try {
      // JWS wants the raw r||s form, not DER.
      Signature ecdsa = Signature.getInstance("SHA256withECDSAinP1363Format");
      ecdsa.initSign(settings.key());
      ecdsa.update((header + "." + claims).getBytes(StandardCharsets.US_ASCII));
      providerToken = header + "." + claims + "." + b64.encodeToString(ecdsa.sign());
      providerTokenMade = now;
      log.debug("Made a new APNs provider token for key {}", settings.keyId());
      return providerToken;
    } catch (java.security.GeneralSecurityException e) {
      throw new IllegalStateException("Could not sign the APNs provider token", e);
    }
  }

  /** Reads the PKCS#8 EC key Apple hands out as an AuthKey_XXXX.p8 file. */
  static PrivateKey readKey(String pem) {
    String base64 = pem.replace("-----BEGIN PRIVATE KEY-----", "")
        .replace("-----END PRIVATE KEY-----", "").replaceAll("\\s", "");
    try {
      return KeyFactory.getInstance("EC")
          .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
    } catch (java.security.GeneralSecurityException | IllegalArgumentException e) {
      throw new IllegalArgumentException("Not an APNs auth key (.p8 file)", e);
    }
  }

  private static String trim(String text) {
    if (text == null) return "";
    return text.length() <= 300 ? text : text.substring(0, 300);
  }
}
