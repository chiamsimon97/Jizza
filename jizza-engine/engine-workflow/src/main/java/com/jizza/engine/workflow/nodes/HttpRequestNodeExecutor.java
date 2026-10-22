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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;
import org.springframework.stereotype.Component;

/**
 * Calls a URL (Zapier's and Zoho Flow's "Send webhook"). Every text setting is a template with
 * {@code {{ }}} placeholders.
 *
 * <ul>
 *   <li>{@code method} and {@code url}; {@code queryParams} (name to value) are added to the URL,
 *       encoded.
 *   <li>{@code headers} (name to value).
 *   <li>{@code auth}: {@code {"type": "basic", "username", "password"}},
 *       {@code {"type": "bearer", "token"}}, or {@code {"type": "apiKey", "name", "value", "in":
 *       "header" | "query"}}.
 *   <li>{@code bodyType}: {@code json} or {@code form} sends {@code fields} (name to value) as a
 *       JSON object or as form data; a JSON field that is one placeholder keeps its type,
 *       {@code unflatten} turns {@code a.b} names into nested objects and {@code wrapInArray}
 *       sends {@code [ {...} ]}. {@code raw} sends {@code body} as it is, with
 *       {@code contentType}. {@code expression} sends {@code bodyExpr} as JSON. {@code none}
 *       sends nothing. Without a {@code bodyType}, {@code bodyExpr} wins over {@code body}, as
 *       before these options existed.
 *   <li>{@code responseType}: {@code auto} (JSON when it looks like JSON), {@code json} or
 *       {@code text}.
 * </ul>
 *
 * <p>The output is the response's {@code status}, {@code ok}, {@code headers} and {@code body}.
 *
 * <p>Every call goes through the same egress guard as connectors: no private networks, no plain
 * http unless allowed, capped size and time, and redirects re-checked. A server error or a 429 is
 * retried with the step's retry policy; another non-2xx fails the step unless
 * {@code failOnError} is false, in which case later steps can look at {@code ok}. Credentials
 * typed into {@code auth} are stored with the workflow; for an API that needs a real login, a
 * connector keeps the credential out of it.
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
      Map<String, String> query = renderPairs(config.path("queryParams"), variables);
      JsonNode auth = config.path("auth");
      switch (auth.path("type").asText("none")) {
        case "basic" -> {
          String pair = render(auth.path("username").asText(""), variables) + ":"
              + render(auth.path("password").asText(""), variables);
          headers.put("Authorization",
              "Basic " + Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8)));
        }
        case "bearer" -> headers.put("Authorization",
            "Bearer " + render(auth.path("token").asText(""), variables).strip());
        case "apiKey" -> {
          String name = render(auth.path("name").asText(""), variables).strip();
          String value = render(auth.path("value").asText(""), variables);
          if (!name.isEmpty()) {
            if ("query".equals(auth.path("in").asText("header"))) query.put(name, value);
            else headers.put(name, value);
          }
        }
        default -> { }
      }
      // The step's own headers come last, so they can override what auth set.
      headers.putAll(renderPairs(config.path("headers"), variables));
      uri = withQuery(render(config.path("url").asText(""), variables).strip(), query);
      body = body(config, variables, headers);
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
    String responseType = config.path("responseType").asText("auto");
    boolean asJson = switch (responseType) {
      case "json" -> true;
      case "text" -> false;
      default -> contentType.contains("json") || looksLikeJson(response.body());
    };
    if (asJson) {
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

  /** The request body for the step's body type, setting a Content-Type unless one is set. */
  private String body(JsonNode config, Map<String, Object> variables, Map<String, String> headers) {
    String bodyType = config.path("bodyType").asText("");
    switch (bodyType) {
      case "none" -> { return null; }
      case "json" -> {
        ObjectNode object = Json.obj();
        boolean unflatten = config.path("unflatten").asBoolean(false);
        for (Map.Entry<String, JsonNode> f : fields(config.path("fields"))) {
          JsonNode value = Templates.renderValue(f.getValue().asText(), sandbox, variables);
          if (unflatten) put(object, f.getKey().split("\\."), value);
          else object.set(f.getKey(), value);
        }
        putContentType(headers, "application/json");
        if (config.path("wrapInArray").asBoolean(false)) {
          return Json.write(Json.mapper().createArrayNode().add(object));
        }
        return Json.write(object);
      }
      case "form" -> {
        StringJoiner form = new StringJoiner("&");
        for (Map.Entry<String, String> f : renderPairs(config.path("fields"), variables).entrySet()) {
          form.add(URLEncoder.encode(f.getKey(), StandardCharsets.UTF_8) + "="
              + URLEncoder.encode(f.getValue(), StandardCharsets.UTF_8));
        }
        putContentType(headers, "application/x-www-form-urlencoded");
        return form.toString();
      }
      case "raw" -> {
        String raw = render(config.path("body").asText(""), variables);
        String type = config.path("contentType").asText("").strip();
        putContentType(headers, !type.isEmpty() ? type
            : looksLikeJson(raw) ? "application/json" : "text/plain; charset=utf-8");
        return raw;
      }
      case "expression" -> {
        putContentType(headers, "application/json");
        return sandbox.evaluate(config.path("bodyExpr").asText(""), variables).toString();
      }
      default -> {
        // Settings saved before body types existed.
        if (config.hasNonNull("bodyExpr")) {
          putContentType(headers, "application/json");
          return sandbox.evaluate(config.get("bodyExpr").asText(), variables).toString();
        }
        String body = render(config.path("body").asText(null), variables);
        if (body != null && looksLikeJson(body)) putContentType(headers, "application/json");
        return body;
      }
    }
  }

  private String render(String template, Map<String, Object> variables) {
    return Templates.render(template, sandbox, variables);
  }

  /** Name-to-template pairs rendered, skipping blank names. */
  private Map<String, String> renderPairs(JsonNode pairs, Map<String, Object> variables) {
    Map<String, String> out = new LinkedHashMap<>();
    for (Map.Entry<String, JsonNode> p : fields(pairs)) {
      JsonNode v = p.getValue();
      out.put(p.getKey(), render(v.isValueNode() ? v.asText() : v.toString(), variables));
    }
    return out;
  }

  private static Iterable<Map.Entry<String, JsonNode>> fields(JsonNode pairs) {
    if (!pairs.isObject()) return List.of();
    return pairs.properties().stream().filter(p -> !p.getKey().isBlank()).toList();
  }

  /** Sets a value at a dotted path, making the objects on the way. */
  private static void put(ObjectNode object, String[] path, JsonNode value) {
    ObjectNode at = object;
    for (int i = 0; i < path.length - 1; i++) {
      JsonNode next = at.get(path[i]);
      at = next instanceof ObjectNode o ? o : at.putObject(path[i]);
    }
    at.set(path[path.length - 1], value);
  }

  private static void putContentType(Map<String, String> headers, String type) {
    if (headers.keySet().stream().noneMatch("content-type"::equalsIgnoreCase)) {
      headers.put("Content-Type", type);
    }
  }

  private static URI withQuery(String url, Map<String, String> query) {
    if (query.isEmpty()) return URI.create(url);
    StringJoiner extra = new StringJoiner("&");
    query.forEach((k, v) -> extra.add(encode(k) + "=" + encode(v)));
    int hash = url.indexOf('#');
    String fragment = hash < 0 ? "" : url.substring(hash);
    String base = hash < 0 ? url : url.substring(0, hash);
    String joiner = !base.contains("?") ? "?" : base.endsWith("?") || base.endsWith("&") ? "" : "&";
    return URI.create(base + joiner + extra + fragment);
  }

  private static String encode(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
  }

  private static boolean looksLikeJson(String text) {
    if (text == null) return false;
    String t = text.strip();
    return (t.startsWith("{") && t.endsWith("}")) || (t.startsWith("[") && t.endsWith("]"));
  }
}
