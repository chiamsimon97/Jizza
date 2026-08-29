package com.jizza.app.e2e;

import com.jizza.common.util.Json;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A real third-party service, on loopback: an OAuth2 token endpoint and a small pet API.
 *
 * <p>Using an actual HTTP server rather than a mocked client is the point. It means the acceptance
 * tests exercise the token exchange, the auth header the executor injects, the egress guard and the
 * response handling as they will actually behave, instead of asserting against a stub of our own
 * code.
 */
public final class StubService implements AutoCloseable {

  private final HttpServer server;

  /** Every request the service received, so tests can assert on what was actually sent. */
  public final List<Recorded> received = java.util.Collections.synchronizedList(new ArrayList<>());

  public final AtomicInteger petsCreated = new AtomicInteger();

  /** Set to hand out a different refresh token, which is what token rotation looks like. */
  public volatile String refreshTokenToIssue = "refresh-token-1";
  public volatile String accessTokenToIssue = "access-token-1";
  public volatile int nextStatus = 200;
  public volatile boolean revoked;

  public record Recorded(String method, String path, String body, Map<String, String> headers) {}

  public StubService() {
    try {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    } catch (IOException e) {
      throw new IllegalStateException("Could not start the stub service", e);
    }
    server.createContext("/oauth/token", this::token);
    server.createContext("/oauth/revoke", this::revoke);
    server.createContext("/v1/", this::api);
    server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
    server.start();
  }

  public String baseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  public String tokenUrl() { return baseUrl() + "/oauth/token"; }

  public String authorizeUrl() { return baseUrl() + "/oauth/authorize"; }

  public String revokeUrl() { return baseUrl() + "/oauth/revoke"; }

  public String apiBaseUrl() { return baseUrl() + "/v1"; }

  /** The OpenAPI document a user would attach for this service. */
  public String openApiDocument() {
    return """
        {
          "openapi": "3.0.3",
          "info": { "title": "Pet Store", "version": "1.0.0" },
          "servers": [ { "url": "%s" } ],
          "paths": {
            "/pets": {
              "get": {
                "operationId": "listPets",
                "summary": "List all pets",
                "parameters": [
                  { "name": "limit", "in": "query", "schema": { "type": "integer" } },
                  { "name": "Authorization", "in": "header", "schema": { "type": "string" } }
                ],
                "responses": { "200": { "description": "ok" } }
              },
              "post": {
                "operationId": "createPet",
                "summary": "Add a pet",
                "requestBody": {
                  "required": true,
                  "content": { "application/json": { "schema": {
                    "type": "object", "required": ["name"],
                    "properties": { "name": { "type": "string" } } } } }
                },
                "responses": { "201": { "description": "created" } }
              }
            },
            "/pets/{petId}": {
              "get": {
                "operationId": "showPetById",
                "summary": "Fetch one pet",
                "parameters": [
                  { "name": "petId", "in": "path", "required": true,
                    "schema": { "type": "string" } }
                ],
                "responses": { "200": { "description": "ok" } }
              }
            }
          }
        }
        """.formatted(apiBaseUrl());
  }

  private void token(HttpExchange exchange) throws IOException {
    String body = read(exchange);
    record(exchange, body);

    Map<String, String> form = parseForm(body);
    if (!"client-abc".equals(form.get("client_id"))) {
      respond(exchange, 401, "{\"error\":\"invalid_client\"}");
      return;
    }
    var response = Json.obj()
        .put("access_token", accessTokenToIssue)
        .put("token_type", "Bearer")
        .put("expires_in", 3600);
    if (refreshTokenToIssue != null) response.put("refresh_token", refreshTokenToIssue);
    respond(exchange, 200, Json.write(response));
  }

  private void revoke(HttpExchange exchange) throws IOException {
    record(exchange, read(exchange));
    revoked = true;
    respond(exchange, 200, "{}");
  }

  private void api(HttpExchange exchange) throws IOException {
    String body = read(exchange);
    record(exchange, body);

    String auth = exchange.getRequestHeaders().getFirst("Authorization");
    if (auth == null || !auth.startsWith("Bearer ")) {
      respond(exchange, 401, "{\"error\":\"missing token\"}");
      return;
    }
    if (nextStatus != 200) {
      int status = nextStatus;
      respond(exchange, status, "{\"error\":\"upstream said " + status + "\"}");
      return;
    }

    String path = exchange.getRequestURI().getPath();
    if (path.equals("/v1/pets") && exchange.getRequestMethod().equals("POST")) {
      petsCreated.incrementAndGet();
      respond(exchange, 200, "{\"id\":\"pet-" + petsCreated.get() + "\",\"created\":true}");
      return;
    }
    if (path.startsWith("/v1/pets/")) {
      respond(exchange, 200, "{\"id\":\"" + path.substring("/v1/pets/".length()) + "\"}");
      return;
    }
    respond(exchange, 200, "{\"pets\":[{\"id\":\"pet-1\",\"name\":\"Rex\"}]}");
  }

  /** The token the executor most recently presented, for asserting server-side injection. */
  public String lastBearerToken() {
    synchronized (received) {
      for (int i = received.size() - 1; i >= 0; i--) {
        String auth = received.get(i).headers().get("authorization");
        if (auth != null && auth.startsWith("Bearer ")) return auth.substring("Bearer ".length());
      }
    }
    return null;
  }

  public long callsTo(String pathPrefix) {
    synchronized (received) {
      return received.stream().filter(r -> r.path().startsWith(pathPrefix)).count();
    }
  }

  private void record(HttpExchange exchange, String body) {
    Map<String, String> headers = new HashMap<>();
    exchange.getRequestHeaders().forEach((name, values) ->
        headers.put(name.toLowerCase(java.util.Locale.ROOT), String.join(",", values)));
    received.add(new Recorded(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
        body, headers));
  }

  private String read(HttpExchange exchange) throws IOException {
    return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
  }

  private void respond(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  public static Map<String, String> parseForm(String body) {
    Map<String, String> form = new HashMap<>();
    if (body == null || body.isBlank()) return form;
    for (String pair : body.split("&")) {
      int eq = pair.indexOf('=');
      if (eq < 0) continue;
      form.put(java.net.URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
          java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
    }
    return form;
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
