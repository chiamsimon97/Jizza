package com.jizza.app.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.util.Json;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A browser-shaped HTTP client for the acceptance suite: it keeps cookies and echoes the CSRF token
 * back as a header, which is exactly what the real client has to do. Using it means the tests
 * exercise the session and CSRF machinery rather than bypassing it.
 */
public final class ApiClient {

  private final String baseUrl;
  private final HttpClient http = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(10))
      .followRedirects(HttpClient.Redirect.NEVER)
      .build();
  private final Map<String, String> cookies = new HashMap<>();
  private java.net.http.HttpHeaders lastHeaders;

  public ApiClient(int port) {
    this.baseUrl = "http://localhost:" + port;
  }

  public record Result(int status, String body) {

    public JsonNode json() {
      return body == null || body.isBlank() ? Json.obj() : Json.read(body);
    }

    public boolean ok() { return status >= 200 && status < 300; }

    public String problemType() { return json().path("type").asText(""); }
  }

  public Result get(String path) { return send("GET", path, null); }

  public Result post(String path, Object body) { return send("POST", path, body); }

  public Result patch(String path, Object body) { return send("PATCH", path, body); }

  public Result put(String path, Object body) { return send("PUT", path, body); }

  public Result delete(String path) { return send("DELETE", path, null); }

  public Result send(String method, String path, Object body) {
    return send(method, path, body == null ? null : Json.write(body), true);
  }

  /** Sends {@code rawBody} exactly as given, e.g. malformed JSON. */
  public Result sendRaw(String method, String path, String rawBody) {
    return send(method, path, rawBody, true);
  }

  /** As {@link #send}, but leaves the CSRF header off, as a forged cross-site request would. */
  public Result sendWithoutCsrf(String method, String path, Object body) {
    return send(method, path, body == null ? null : Json.write(body), false);
  }

  /** As {@link #send}, with extra request headers. */
  public Result sendWithHeaders(String method, String path, Object body,
                                java.util.Map<String, String> headers) {
    return send(method, path, body == null ? null : Json.write(body), true, headers);
  }

  private Result send(String method, String path, String body, boolean withCsrf) {
    return send(method, path, body, withCsrf, java.util.Map.of());
  }

  private Result send(String method, String path, String body, boolean withCsrf,
                      java.util.Map<String, String> headers) {
    HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path))
        .timeout(Duration.ofSeconds(60))
        .header("accept", "application/json");
    headers.forEach(request::header);

    if (body != null) {
      request.header("content-type", "application/json");
      request.method(method, HttpRequest.BodyPublishers.ofString(body));
    } else {
      request.method(method, HttpRequest.BodyPublishers.noBody());
    }
    if (!cookies.isEmpty()) request.header("cookie", cookieHeader());
    // Spring's cookie CSRF repository expects the token echoed in a header on mutating requests.
    String csrf = cookies.get("XSRF-TOKEN");
    if (withCsrf && csrf != null && !"GET".equals(method)) request.header("X-XSRF-TOKEN", csrf);

    try {
      HttpResponse<String> response = http.send(request.build(),
          HttpResponse.BodyHandlers.ofString());
      if (System.getenv("JIZZA_TEST_TRACE") != null) {
        System.out.println("TRACE " + method + " " + path + " -> " + response.statusCode()
            + " sent-cookies=[" + cookieHeader() + "] set-cookie="
            + response.headers().allValues("set-cookie"));
      }
      captureCookies(response);
      lastHeaders = response.headers();
      return new Result(response.statusCode(), response.body());
    } catch (Exception e) {
      throw new IllegalStateException(method + " " + path + " failed", e);
    }
  }

  /** Posts a multipart form with one file and some text fields, as the browser's file upload does. */
  public Result upload(String path, java.util.Map<String, String> fields, String filename, String contentType,
                       byte[] file) {
    String boundary = "----jizza" + java.util.UUID.randomUUID();
    var out = new java.io.ByteArrayOutputStream();
    java.util.function.Consumer<String> text = t -> out.writeBytes(t.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    fields.forEach((name, value) -> {
      if (value == null) return;
      text.accept("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n");
    });
    text.accept("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename
        + "\"\r\nContent-Type: " + contentType + "\r\n\r\n");
    out.writeBytes(file);
    text.accept("\r\n--" + boundary + "--\r\n");
    HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path))
        .timeout(Duration.ofSeconds(60)).header("accept", "application/json")
        .header("content-type", "multipart/form-data; boundary=" + boundary)
        .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray()));
    if (!cookies.isEmpty()) request.header("cookie", cookieHeader());
    String csrf = cookies.get("XSRF-TOKEN");
    if (csrf != null) request.header("X-XSRF-TOKEN", csrf);
    try {
      HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
      captureCookies(response);
      lastHeaders = response.headers();
      return new Result(response.statusCode(), response.body());
    } catch (Exception e) {
      throw new IllegalStateException("POST " + path + " failed", e);
    }
  }

  /** Primes the CSRF cookie, the way loading the app in a browser would. */
  public void prime() {
    get("/actuator/health");
    if (!cookies.containsKey("XSRF-TOKEN")) get("/api/agents");
  }

  public boolean hasSession() { return cookies.containsKey("JIZZA_SESSION"); }

  public void forgetSession() { cookies.remove("JIZZA_SESSION"); }

  public String sessionCookie() { return cookies.get("JIZZA_SESSION"); }

  /** Presents a session token captured earlier, as a replaying attacker would. */
  public void useSessionCookie(String token) { cookies.put("JIZZA_SESSION", token); }

  /** Headers of the most recent response. */
  public java.net.http.HttpHeaders lastHeaders() { return lastHeaders; }

  private void captureCookies(HttpResponse<String> response) {
    List<String> setCookies = response.headers().allValues("set-cookie");
    for (String header : setCookies) {
      String pair = header.split(";", 2)[0];
      int eq = pair.indexOf('=');
      if (eq <= 0) continue;
      String name = pair.substring(0, eq).trim();
      String value = pair.substring(eq + 1).trim();
      if (value.isEmpty()) cookies.remove(name);
      else cookies.put(name, value);
    }
  }

  public String cookieHeader() {
    StringBuilder sb = new StringBuilder();
    cookies.forEach((name, value) -> {
      if (sb.length() > 0) sb.append("; ");
      sb.append(name).append('=').append(value);
    });
    return sb.toString();
  }
}
