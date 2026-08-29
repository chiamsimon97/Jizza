package com.jizza.connectors.tools.execute;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.common.audit.AuditLog;
import com.jizza.common.connector.IdempotencyKey;
import com.jizza.common.connector.ToolExecutor;
import com.jizza.common.crypto.EncryptionService;
import com.jizza.common.dto.GeneratedToolDto;
import com.jizza.common.enums.ActorType;
import com.jizza.common.enums.AuthScheme;
import com.jizza.common.enums.TokenPlacement;
import com.jizza.common.error.EgressBlockedException;
import com.jizza.common.error.NotFoundException;
import com.jizza.common.error.ValidationException;
import com.jizza.common.tool.ToolResult;
import com.jizza.common.util.Hashing;
import com.jizza.common.util.Json;
import com.jizza.common.util.Redaction;
import com.jizza.connectors.http.GuardedHttpClient;
import com.jizza.connectors.oauth.DefaultOAuthService;
import com.jizza.connectors.service.ConnectionService;
import com.jizza.persistence.entity.ConnectionEntity;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Executes a generated tool against the real service (R27).
 *
 * <p>Four things happen here that cannot happen anywhere else: the base URL comes from the
 * connection rather than the tool (so a doctored API document cannot redirect a call), the
 * credential is injected server-side from encrypted storage (so it never enters model context), the
 * egress guard vets the resolved target, and a side-effecting call is de-duplicated by idempotency
 * key so a retried workflow node cannot send the same email twice.
 */
@Service
public class DefaultToolExecutor implements ToolExecutor {

  private static final Logger log = LoggerFactory.getLogger(DefaultToolExecutor.class);

  private final ConnectionService connections;
  private final DefaultOAuthService oauth;
  private final EncryptionService encryption;
  private final GuardedHttpClient http;
  private final IdempotencyService idempotency;
  private final AuditLog audit;

  public DefaultToolExecutor(ConnectionService connections, DefaultOAuthService oauth,
                             EncryptionService encryption, GuardedHttpClient http,
                             IdempotencyService idempotency, AuditLog audit) {
    this.connections = connections;
    this.oauth = oauth;
    this.encryption = encryption;
    this.http = http;
    this.idempotency = idempotency;
    this.audit = audit;
  }

  @Override
  public ToolResult execute(GeneratedToolDto tool, JsonNode args, IdempotencyKey key) {
    ConnectionEntity connection = connections.require(tool.connectionId());
    if (!tool.enabled()) {
      return ToolResult.error("Tool '" + tool.name() + "' is disabled");
    }

    JsonNode arguments = args == null ? Json.obj() : args;
    String fingerprint = fingerprint(tool, arguments);

    if (tool.sideEffecting() && key != null) {
      var replay = idempotency.claim(key, tool.name(), fingerprint);
      if (replay.isPresent()) return replay.get();
    }

    ToolResult result;
    try {
      result = call(connection, tool, arguments);
    } catch (EgressBlockedException e) {
      audit.record(connection.getUserId(), ActorType.SYSTEM, null, "tool.egress_blocked",
          tool.name(), Json.obj().put("reason", e.getMessage()));
      idempotency.release(key, false, null);
      return ToolResult.error(e.getMessage());
    } catch (RuntimeException e) {
      log.warn("Tool {} failed", tool.name(), e);
      idempotency.release(key, false, null);
      return ToolResult.error(Redaction.scrub(String.valueOf(e.getMessage())));
    }

    idempotency.release(key, result.ok(), result.ok() ? result.output() : null);
    return result;
  }

  private ToolResult call(ConnectionEntity connection, GeneratedToolDto tool, JsonNode args) {
    if (connection.getAuthScheme() == AuthScheme.OAUTH2 && oauth.needsRefresh(connection)) {
      // Refresh transparently so an unattended run does not fail on a token that lapsed overnight.
      oauth.refresh(connection);
    }

    JsonNode mapping = tool.paramMapping() == null ? Json.obj() : tool.paramMapping();
    String path = renderPath(tool.pathTemplate(), mapping, args);

    Map<String, String> query = new LinkedHashMap<>();
    for (JsonNode name : mapping.path("query")) {
      if (args.hasNonNull(name.asText())) {
        query.put(name.asText(), asText(args.get(name.asText())));
      }
    }

    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("accept", "application/json");
    for (JsonNode name : mapping.path("header")) {
      if (args.hasNonNull(name.asText())) headers.put(name.asText(), asText(args.get(name.asText())));
    }

    String body = null;
    String bodyProperty = mapping.path("bodyProperty").asText(null);
    ObjectNode bodyNode = null;
    if (bodyProperty != null && args.hasNonNull(bodyProperty)) {
      bodyNode = args.get(bodyProperty).deepCopy();
      headers.put("content-type", "application/json");
    }

    injectAuth(connection, tool, headers, query, bodyNode);
    if (bodyNode != null) body = Json.write(bodyNode);

    URI target = URI.create(baseUrl(connection) + path + queryString(query));
    GuardedHttpClient.Response response =
        http.send(tool.httpMethod(), target, headers, body, declaredHost(connection));

    ObjectNode output = Json.obj();
    output.put("status", response.status());
    output.set("body", response.json());

    if (response.status() == 429) {
      String retryAfter = response.headers().get("retry-after");
      return ToolResult.error("The service is rate limiting this connection"
          + (retryAfter == null ? "" : "; retry after " + retryAfter + " seconds"));
    }
    if (!response.ok()) {
      // The body can echo request headers back; scrub before it goes anywhere near a model or log.
      return ToolResult.error("The service replied " + response.status() + ": "
          + Redaction.scrub(abbreviate(response.body())));
    }
    return ToolResult.ok(output);
  }

  /**
   * Injects the credential according to the connection's scheme and placement. This is the only
   * place a decrypted token exists, and it never leaves the outbound request.
   */
  private void injectAuth(ConnectionEntity connection, GeneratedToolDto tool,
                          Map<String, String> headers, Map<String, String> query,
                          ObjectNode body) {
    JsonNode authMapping = tool.authMapping();
    switch (connection.getAuthScheme()) {
      case OAUTH2 -> {
        String token = encryption.decryptString(connection.getAccessToken());
        if (token == null) {
          throw new ValidationException(connection.getServiceName()
              + " is not connected yet, so this tool has no credential to use");
        }
        place(connection.getTokenPlacement(), headers, query, body,
            "Authorization", "Bearer " + token, "access_token", token);
      }
      case API_KEY -> {
        String apiKey = connections.decryptClientSecret(connection);
        if (apiKey == null) {
          throw new ValidationException(connection.getServiceName() + " has no API key configured");
        }
        String name = authMapping != null && authMapping.hasNonNull("name")
            ? authMapping.get("name").asText() : "Authorization";
        String prefix = authMapping != null && authMapping.hasNonNull("prefix")
            ? authMapping.get("prefix").asText() : "";
        String in = authMapping != null && authMapping.hasNonNull("in")
            ? authMapping.get("in").asText() : "header";
        if ("query".equals(in)) {
          query.put(name, apiKey);
        } else {
          headers.put(name, prefix + apiKey);
        }
      }
      case BASIC -> {
        String secret = connections.decryptClientSecret(connection);
        String credentials = connection.getClientId() + ":" + (secret == null ? "" : secret);
        headers.put("Authorization", "Basic " + java.util.Base64.getEncoder()
            .encodeToString(credentials.getBytes(StandardCharsets.UTF_8)));
      }
      case NO_AUTH, CUSTOM -> { /* Nothing to inject; CUSTOM relies on configured headers. */ }
    }
  }

  private void place(TokenPlacement placement, Map<String, String> headers,
                     Map<String, String> query, ObjectNode body,
                     String headerName, String headerValue, String paramName, String rawToken) {
    switch (placement == null ? TokenPlacement.HEADER : placement) {
      case HEADER -> headers.put(headerName, headerValue);
      case QUERY -> query.put(paramName, rawToken);
      case BODY -> {
        if (body != null) body.put(paramName, rawToken);
        else headers.put(headerName, headerValue);
      }
    }
  }

  private String renderPath(String template, JsonNode mapping, JsonNode args) {
    String path = template;
    for (JsonNode name : mapping.path("path")) {
      String parameter = name.asText();
      if (!args.hasNonNull(parameter)) {
        throw new ValidationException("Missing path parameter '" + parameter + "'");
      }
      String value = URLEncoder.encode(asText(args.get(parameter)), StandardCharsets.UTF_8);
      path = path.replace("{" + parameter + "}", value);
    }
    if (path.matches(".*\\{[^}]+}.*")) {
      throw new ValidationException("The request path still has unfilled placeholders: " + path);
    }
    return path.startsWith("/") ? path : "/" + path;
  }

  private String queryString(Map<String, String> query) {
    if (query.isEmpty()) return "";
    StringBuilder sb = new StringBuilder("?");
    query.forEach((key, value) -> {
      if (sb.length() > 1) sb.append('&');
      sb.append(URLEncoder.encode(key, StandardCharsets.UTF_8)).append('=')
          .append(URLEncoder.encode(value, StandardCharsets.UTF_8));
    });
    return sb.toString();
  }

  /** The host is the connection's, never the tool document's. */
  private String baseUrl(ConnectionEntity connection) {
    String base = connection.getBaseUrl();
    if (base == null || base.isBlank()) {
      throw new ValidationException(connection.getServiceName()
          + " has no base URL configured, so its tools have nowhere to call");
    }
    return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
  }

  private String declaredHost(ConnectionEntity connection) {
    return connection.getBaseUrl();
  }

  private String fingerprint(GeneratedToolDto tool, JsonNode args) {
    return Hashing.sha256Hex(tool.httpMethod() + " " + tool.pathTemplate() + " " + Json.write(args));
  }

  private String asText(JsonNode node) {
    return node.isValueNode() ? node.asText() : Json.write(node);
  }

  private String abbreviate(String body) {
    if (body == null) return "";
    return body.length() <= 500 ? body : body.substring(0, 500) + "...";
  }
}
