package com.jizza.engine.workflow.nodes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.enums.NodeType;
import com.jizza.engine.api.error.EgressBlockedException;
import com.jizza.engine.api.error.SandboxViolationException;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.connectors.http.GuardedHttpClient;
import com.jizza.engine.workflow.engine.NodeExecutor;
import com.jizza.engine.workflow.sandbox.JexlSandbox;
import com.jizza.engine.workflow.sandbox.Templates;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Calls a URL (Zapier's and Zoho Flow's "Send webhook"): {@code method}, {@code url},
 * {@code headers} and {@code body} are templates with {@code {{ }}} placeholders, or
 * {@code bodyExpr} builds the body as JSON from the run's data. The output is the response's
 * {@code status}, {@code ok}, {@code headers} and {@code body} (parsed when it is JSON).
 *
 * <p>Every call goes through the same egress guard as connectors: no private networks, no plain
 * http unless allowed, capped size and time, and redirects re-checked. A server error or a 429 is
 * retried with the step's retry policy; another non-2xx fails the step unless
 * {@code failOnError} is false, in which case later steps can look at {@code ok}. For an API that
 * needs a login, use a connector instead, which keeps the credential out of the workflow.
 */
@Component
public class HttpRequestNodeExecutor implements NodeExecutor {

  static final int MAX_BODY_CHARS = 1_000_000;

  private final JexlSandbox sandbox;
  private final GuardedHttpClient http;

  public HttpRequestNodeExecutor(JexlSandbox sandbox, GuardedHttpClient http) {
    this.sandbox = sandbox;
    this.http = http;
  }

  @Override
  public NodeType type() { return NodeType.HTTP_REQUEST; }

  @Override
  public Outcome execute(Execution execution) {
    JsonNode config = execution.node().config();
    if (config == null) return Outcome.permanentFailure("The webhook step has no settings");
    Map<String, Object> variables = execution.context().variables();

    String method = config.path("method").asText("POST").toUpperCase(Locale.ROOT);
    URI uri;
    Map<String, String> headers = new LinkedHashMap<>();
    String body;
    try {
      uri = URI.create(Templates.render(config.path("url").asText(""), sandbox, variables).strip());
      if (config.path("headers").isObject()) {
        for (Map.Entry<String, JsonNode> h : config.get("headers").properties()) {
          headers.put(h.getKey(), Templates.render(h.getValue().asText(), sandbox, variables));
        }
      }
      if (config.hasNonNull("bodyExpr")) {
        body = sandbox.evaluate(config.get("bodyExpr").asText(), variables).toString();
        headers.putIfAbsent("Content-Type", "application/json");
      } else {
        body = Templates.render(config.path("body").asText(null), sandbox, variables);
        if (body != null && looksLikeJson(body)) headers.putIfAbsent("Content-Type", "application/json");
      }
    } catch (SandboxViolationException e) {
      return Outcome.permanentFailure("The webhook could not be put together: " + e.getMessage());
    } catch (IllegalArgumentException e) {
      return Outcome.permanentFailure("The webhook URL is not valid: " + e.getMessage());
    }
    if (method.equals("GET") || method.equals("DELETE")) body = null;
    if (body != null && body.length() > MAX_BODY_CHARS) {
      return Outcome.permanentFailure("The webhook body is larger than " + MAX_BODY_CHARS
          + " characters");
    }
    if (uri.getHost() == null) return Outcome.permanentFailure("The webhook URL has no host");

    GuardedHttpClient.Response response;
    try {
      response = http.send(method, uri, headers, body, uri.getHost());
    } catch (EgressBlockedException e) {
      return Outcome.permanentFailure("The webhook was blocked: " + e.getMessage());
    } catch (RuntimeException e) {
      return Outcome.retryable("The webhook could not reach " + uri.getHost() + ": " + e.getMessage());
    }

    ObjectNode output = Json.obj().put("status", response.status()).put("ok", response.ok());
    ObjectNode responseHeaders = output.putObject("headers");
    response.headers().forEach(responseHeaders::put);
    String contentType = response.headers().getOrDefault("content-type", "");
    if (contentType.contains("json") || looksLikeJson(response.body())) {
      output.set("body", response.json());
    } else {
      output.put("body", response.body());
    }

    if (response.ok() || !config.path("failOnError").asBoolean(true)) {
      return Outcome.completed(output);
    }
    String detail = method + " " + uri.getHost() + " answered " + response.status();
    if (response.status() >= 500 || response.status() == 429) return Outcome.retryable(detail);
    return Outcome.permanentFailure(detail);
  }

  private static boolean looksLikeJson(String text) {
    if (text == null) return false;
    String t = text.strip();
    return (t.startsWith("{") && t.endsWith("}")) || (t.startsWith("[") && t.endsWith("]"));
  }
}
