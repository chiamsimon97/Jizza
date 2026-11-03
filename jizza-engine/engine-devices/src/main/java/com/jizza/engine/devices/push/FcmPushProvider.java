package com.jizza.engine.devices.push;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.device.PushProvider;
import com.jizza.engine.api.util.Json;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wakes Android phones through Firebase Cloud Messaging (HTTP v1 API).
 *
 * <p>It signs in to Google with the Firebase project's service account key: a JWT signed with the
 * account's private key is traded for an access token, kept until shortly before it runs out. Each
 * push is a high-priority data message carrying only the ids {@link PushDispatcher} gives it; FCM
 * data values must be strings, so other values are written as JSON text.
 *
 * <p>Only {@link FcmPushConfig} creates it, and only when a key file is configured.
 */
public class FcmPushProvider implements PushProvider {

  private static final Logger log = LoggerFactory.getLogger(FcmPushProvider.class);
  static final String SCOPE = "https://www.googleapis.com/auth/firebase.messaging";
  static final String DEFAULT_SEND_BASE = "https://fcm.googleapis.com";

  /** What the provider needs from a service account key file. */
  record ServiceAccount(String projectId, String clientEmail, PrivateKey privateKey,
                        String tokenUri) {}

  private final ServiceAccount account;
  private final String sendBase;
  private final HttpClient http;
  private final Clock clock;

  private String accessToken;
  private Instant accessTokenExpires = Instant.EPOCH;

  FcmPushProvider(ServiceAccount account, String sendBase, HttpClient http, Clock clock) {
    this.account = account;
    this.sendBase = sendBase.replaceAll("/+$", "");
    this.http = http;
    this.clock = clock;
  }

  static FcmPushProvider fromFile(Path file) throws IOException {
    return new FcmPushProvider(read(Json.read(Files.readString(file))), DEFAULT_SEND_BASE,
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), Clock.systemUTC());
  }

  @Override
  public String name() {
    return "fcm";
  }

  @Override
  public boolean send(String token, JsonNode data) {
    ObjectNode strings = Json.obj();
    if (data != null && data.isObject()) {
      for (Iterator<Map.Entry<String, JsonNode>> it = data.fields(); it.hasNext(); ) {
        var e = it.next();
        strings.put(e.getKey(), e.getValue().isTextual() ? e.getValue().asText()
            : Json.write(e.getValue()));
      }
    }
    ObjectNode message = Json.obj();
    message.put("token", token);
    message.set("data", strings);
    message.putObject("android").put("priority", "HIGH").put("ttl", "3600s");
    ObjectNode body = Json.obj();
    body.set("message", message);

    HttpResponse<String> response = post(body, false);
    if (response.statusCode() == 401) response = post(body, true);
    int status = response.statusCode();
    if (status >= 200 && status < 300) return true;
    if (tokenGone(status, response.body())) return false;
    throw new IllegalStateException("FCM answered " + status + ": " + trim(response.body()));
  }

  private HttpResponse<String> post(ObjectNode body, boolean freshToken) {
    String bearer = accessToken(freshToken);
    HttpRequest request = HttpRequest.newBuilder(
            URI.create(sendBase + "/v1/projects/" + account.projectId() + "/messages:send"))
        .timeout(Duration.ofSeconds(15))
        .header("Authorization", "Bearer " + bearer)
        .header("Content-Type", "application/json; charset=UTF-8")
        .POST(HttpRequest.BodyPublishers.ofString(Json.write(body)))
        .build();
    return exchange(request);
  }

  /** A 404, or a 400 that says the token itself is bad, means the app was removed or re-installed. */
  static boolean tokenGone(int status, String body) {
    if (status == 404) return true;
    if (status != 400 || body == null) return false;
    return body.contains("UNREGISTERED") || body.contains("registration token is not a valid");
  }

  synchronized String accessToken(boolean fresh) {
    Instant now = clock.instant();
    if (!fresh && accessToken != null && now.isBefore(accessTokenExpires.minusSeconds(120))) {
      return accessToken;
    }
    String assertion = jwt(now);
    String form = "grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:jwt-bearer",
        StandardCharsets.UTF_8) + "&assertion=" + URLEncoder.encode(assertion, StandardCharsets.UTF_8);
    HttpRequest request = HttpRequest.newBuilder(URI.create(account.tokenUri()))
        .timeout(Duration.ofSeconds(15))
        .header("Content-Type", "application/x-www-form-urlencoded")
        .POST(HttpRequest.BodyPublishers.ofString(form))
        .build();
    HttpResponse<String> response = exchange(request);
    if (response.statusCode() != 200) {
      throw new IllegalStateException("Google refused the Firebase service account ("
          + response.statusCode() + "): " + trim(response.body()));
    }
    JsonNode answer = Json.read(response.body());
    accessToken = answer.path("access_token").asText(null);
    if (accessToken == null) throw new IllegalStateException("Google sent no access token");
    accessTokenExpires = now.plusSeconds(answer.path("expires_in").asLong(3600));
    log.debug("Signed in to FCM as {}", account.clientEmail());
    return accessToken;
  }

  String jwt(Instant now) {
    Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
    String header = b64.encodeToString("{\"alg\":\"RS256\",\"typ\":\"JWT\"}"
        .getBytes(StandardCharsets.UTF_8));
    ObjectNode claims = Json.obj()
        .put("iss", account.clientEmail())
        .put("scope", SCOPE)
        .put("aud", account.tokenUri())
        .put("iat", now.getEpochSecond())
        .put("exp", now.plusSeconds(3600).getEpochSecond());
    String payload = b64.encodeToString(Json.write(claims).getBytes(StandardCharsets.UTF_8));
    try {
      Signature rsa = Signature.getInstance("SHA256withRSA");
      rsa.initSign(account.privateKey());
      rsa.update((header + "." + payload).getBytes(StandardCharsets.US_ASCII));
      return header + "." + payload + "." + b64.encodeToString(rsa.sign());
    } catch (java.security.GeneralSecurityException e) {
      throw new IllegalStateException("Could not sign the FCM sign-in token", e);
    }
  }

  private HttpResponse<String> exchange(HttpRequest request) {
    try {
      return http.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new IllegalStateException("Could not reach " + request.uri().getHost(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while pushing", e);
    }
  }

  static ServiceAccount read(JsonNode key) {
    String projectId = key.path("project_id").asText(null);
    String email = key.path("client_email").asText(null);
    String pem = key.path("private_key").asText(null);
    String tokenUri = key.path("token_uri").asText("https://oauth2.googleapis.com/token");
    if (projectId == null || email == null || pem == null) {
      throw new IllegalArgumentException("Not a Firebase service account key: it needs project_id, "
          + "client_email and private_key");
    }
    String base64 = pem.replace("-----BEGIN PRIVATE KEY-----", "")
        .replace("-----END PRIVATE KEY-----", "").replaceAll("\\s", "");
    try {
      PrivateKey privateKey = KeyFactory.getInstance("RSA")
          .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
      return new ServiceAccount(projectId, email, privateKey, tokenUri);
    } catch (java.security.GeneralSecurityException | IllegalArgumentException e) {
      throw new IllegalArgumentException("The service account's private_key could not be read", e);
    }
  }

  private static String trim(String text) {
    if (text == null) return "";
    return text.length() <= 300 ? text : text.substring(0, 300);
  }
}
