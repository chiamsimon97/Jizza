package com.jizza.app.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.common.util.Json;
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
    HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path))
        .timeout(Duration.ofSeconds(60))
        .header("accept", "application/json");

    if (body != null) {
      request.header("content-type", "application/json");
      request.method(method, HttpRequest.BodyPublishers.ofString(Json.write(body)));
    } else {
      request.method(method, HttpRequest.BodyPublishers.noBody());
    }
    if (!cookies.isEmpty()) request.header("cookie", cookieHeader());
    // Spring's cookie CSRF repository expects the token echoed in a header on mutating requests.
    String csrf = cookies.get("XSRF-TOKEN");
    if (csrf != null && !"GET".equals(method)) request.header("X-XSRF-TOKEN", csrf);

    try {
      HttpResponse<String> response = http.send(request.build(),
          HttpResponse.BodyHandlers.ofString());
      if (System.getenv("JIZZA_TEST_TRACE") != null) {
        System.out.println("TRACE " + method + " " + path + " -> " + response.statusCode()
            + " sent-cookies=[" + cookieHeader() + "] set-cookie="
            + response.headers().allValues("set-cookie"));
      }
      captureCookies(response);
      return new Result(response.statusCode(), response.body());
    } catch (Exception e) {
      throw new IllegalStateException(method + " " + path + " failed", e);
    }
  }

  /** Primes the CSRF cookie, the way loading the app in a browser would. */
  public void prime() {
    get("/actuator/health");
    if (!cookies.containsKey("XSRF-TOKEN")) get("/api/agents");
  }

  public boolean hasSession() { return cookies.containsKey("JIZZA_SESSION"); }

  public void forgetSession() { cookies.remove("JIZZA_SESSION"); }

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

  private String cookieHeader() {
    StringBuilder sb = new StringBuilder();
    cookies.forEach((name, value) -> {
      if (sb.length() > 0) sb.append("; ");
      sb.append(name).append('=').append(value);
    });
    return sb.toString();
  }
}
