package com.jizza.engine.connectors.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.connector.EgressGuard;
import com.jizza.engine.api.error.EgressBlockedException;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.connectors.config.ConnectorProperties;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The only way this platform makes an outbound call on the user's behalf. Every request is checked
 * by the {@link EgressGuard} first, capped by timeout and response size, and -- crucially -- follows
 * no redirects automatically: a 302 to {@code 169.254.169.254} would otherwise walk straight past a
 * guard that only ever saw the original URL.
 */
@Component
public class GuardedHttpClient {

  /** How many redirects to follow, each re-checked by the guard. */
  private static final int MAX_REDIRECTS = 3;

  private final EgressGuard guard;
  private final ConnectorProperties props;
  private final HttpClient client;

  public GuardedHttpClient(EgressGuard guard, ConnectorProperties props) {
    this.guard = guard;
    this.props = props;
    this.client = HttpClient.newBuilder()
        .connectTimeout(props.getHttp().getConnectTimeout())
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();
  }

  public record Response(int status, String body, Map<String, String> headers) {

    public boolean ok() { return status >= 200 && status < 300; }

    public JsonNode json() {
      try {
        return body == null || body.isBlank() ? Json.obj() : Json.read(body);
      } catch (RuntimeException e) {
        return Json.obj().put("raw", body);
      }
    }
  }

  public Response send(String method, URI uri, Map<String, String> headers, String body,
                       String declaredHost) {
    URI target = uri;
    for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
      guard.check(target, declaredHost);
      Response response = exchange(method, target, headers, body);
      if (response.status() / 100 != 3) return response;

      String location = response.headers().get("location");
      if (location == null || location.isBlank()) return response;
      // Re-check the redirect target against the same rules before following it.
      target = target.resolve(location);
      // A redirect turns any request into a GET without a body, matching browser behaviour and
      // avoiding replaying a side-effecting body at a new host.
      method = "GET";
      body = null;
    }
    throw new EgressBlockedException("Refused " + uri + ": too many redirects");
  }

  private Response exchange(String method, URI uri, Map<String, String> headers, String body) {
    HttpRequest.Builder request = HttpRequest.newBuilder(uri)
        .timeout(props.getHttp().getReadTimeout())
        .method(method, body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
    if (headers != null) headers.forEach(request::header);

    try {
      HttpResponse<InputStream> response =
          client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
      String payload = readCapped(response.body());
      return new Response(response.statusCode(), payload,
          response.headers().map().entrySet().stream()
              .collect(java.util.stream.Collectors.toMap(
                  e -> e.getKey().toLowerCase(java.util.Locale.ROOT),
                  e -> String.join(",", e.getValue()),
                  (a, b) -> a)));
    } catch (IOException e) {
      throw new com.jizza.engine.api.error.JizzaException(
          org.springframework.http.HttpStatus.BAD_GATEWAY, "connector-unreachable",
          "Connector call failed", "Could not reach " + uri.getHost() + ": " + e.getMessage(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new com.jizza.engine.api.error.JizzaException(
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "connector-interrupted",
          "Connector call interrupted", "The call to " + uri.getHost() + " was interrupted", e);
    }
  }

  /** Reads at most the configured cap, so a hostile or broken endpoint cannot exhaust memory. */
  private String readCapped(InputStream stream) throws IOException {
    long limit = props.getHttp().getMaxResponseBytes();
    var buffer = new java.io.ByteArrayOutputStream();
    byte[] chunk = new byte[8192];
    long total = 0;
    int read;
    while ((read = stream.read(chunk)) != -1) {
      total += read;
      if (total > limit) {
        throw new EgressBlockedException(
            "The response exceeded the " + limit + " byte cap and was discarded");
      }
      buffer.write(chunk, 0, read);
    }
    return buffer.toString(StandardCharsets.UTF_8);
  }

  public Duration readTimeout() { return props.getHttp().getReadTimeout(); }
}
