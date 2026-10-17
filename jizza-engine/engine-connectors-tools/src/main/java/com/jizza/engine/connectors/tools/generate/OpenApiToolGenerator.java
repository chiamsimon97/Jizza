package com.jizza.engine.connectors.tools.generate;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.dto.GeneratedToolDto;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.util.Json;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Deterministic OpenAPI/Swagger ingestion (R26): one callable tool per operation, with a name, a
 * description and a JSON-Schema input built from the operation's parameters and request body.
 *
 * <p>The document is untrusted input. It is parsed, not executed: nothing in it can name a host the
 * connection did not declare (the executor resolves the base URL from the connection), and every
 * description is trimmed to a label before the model ever sees it.
 */
@Component
public class OpenApiToolGenerator {

  private static final Logger log = LoggerFactory.getLogger(OpenApiToolGenerator.class);

  /** HTTP methods that change something and therefore need an idempotency key (R31). */
  private static final Set<String> SIDE_EFFECTING =
      Set.of("POST", "PUT", "PATCH", "DELETE");

  public record Parsed(List<GeneratedToolDto> tools, String serverUrl, List<String> warnings) {}

  public Parsed parse(java.util.UUID connectionId, byte[] document) {
    ParseOptions options = new ParseOptions();
    options.setResolve(true);
    options.setResolveFully(true);
    options.setFlatten(false);

    SwaggerParseResult result = new OpenAPIV3Parser()
        .readContents(new String(document, StandardCharsets.UTF_8), null, options);
    OpenAPI api = result.getOpenAPI();
    if (api == null || api.getPaths() == null || api.getPaths().isEmpty()) {
      throw new ValidationException("That document could not be read as an OpenAPI specification"
          + (result.getMessages() == null || result.getMessages().isEmpty()
             ? "" : ": " + String.join("; ", result.getMessages())));
    }

    List<String> warnings = new ArrayList<>();
    if (result.getMessages() != null) warnings.addAll(result.getMessages());

    String serverUrl = api.getServers() == null || api.getServers().isEmpty()
        ? null : api.getServers().get(0).getUrl();

    List<GeneratedToolDto> tools = new ArrayList<>();
    Set<String> usedNames = new LinkedHashSet<>();

    api.getPaths().forEach((path, item) -> item.readOperationsMap().forEach((method, operation) -> {
      try {
        tools.add(toTool(connectionId, path, method, operation, item, usedNames));
      } catch (RuntimeException e) {
        log.warn("Skipping {} {} while generating tools", method, path, e);
        warnings.add("Skipped " + method + " " + path + ": " + e.getMessage());
      }
    }));

    if (tools.isEmpty()) {
      throw new ValidationException("That specification has no operations to turn into tools");
    }
    return new Parsed(tools, serverUrl, warnings);
  }

  private GeneratedToolDto toTool(java.util.UUID connectionId, String path, PathItem.HttpMethod method,
                                  Operation operation, PathItem item, Set<String> usedNames) {
    String httpMethod = method.name().toUpperCase(Locale.ROOT);
    String name = uniqueName(operation.getOperationId(), httpMethod, path, usedNames);

    ObjectNode schema = Json.obj();
    schema.put("type", "object");
    ObjectNode properties = schema.putObject("properties");
    ArrayNode required = Json.mapper().createArrayNode();

    ObjectNode mapping = Json.obj();
    ArrayNode pathParams = mapping.putArray("path");
    ArrayNode queryParams = mapping.putArray("query");
    ArrayNode headerParams = mapping.putArray("header");

    List<Parameter> parameters = new ArrayList<>();
    if (item.getParameters() != null) parameters.addAll(item.getParameters());
    if (operation.getParameters() != null) parameters.addAll(operation.getParameters());

    for (Parameter parameter : parameters) {
      if (parameter.getName() == null) continue;
      // A document that tries to define an "authorization" input is asking the model to supply a
      // credential. Auth is injected server-side; drop it.
      if (isAuthParameter(parameter)) continue;

      ObjectNode property = SchemaTranslator.translate(parameter.getSchema());
      if (parameter.getDescription() != null) {
        property.put("description", SchemaTranslator.trim(parameter.getDescription()));
      }
      properties.set(parameter.getName(), property);
      if (Boolean.TRUE.equals(parameter.getRequired())) required.add(parameter.getName());

      switch (String.valueOf(parameter.getIn())) {
        case "path" -> pathParams.add(parameter.getName());
        case "header" -> headerParams.add(parameter.getName());
        default -> queryParams.add(parameter.getName());
      }
    }

    RequestBody body = operation.getRequestBody();
    if (body != null && body.getContent() != null) {
      var media = body.getContent().get("application/json");
      if (media != null && media.getSchema() != null) {
        ObjectNode bodySchema = SchemaTranslator.translate(media.getSchema());
        properties.set("body", bodySchema);
        mapping.put("bodyProperty", "body");
        if (Boolean.TRUE.equals(body.getRequired())) required.add("body");
      }
    }

    if (!required.isEmpty()) schema.set("required", required);
    schema.put("additionalProperties", false);

    String description = describe(operation, httpMethod, path);
    boolean sideEffecting = SIDE_EFFECTING.contains(httpMethod);

    return new GeneratedToolDto(null, connectionId, name, description, schema, httpMethod, path,
        mapping, null, sideEffecting, true);
  }

  private boolean isAuthParameter(Parameter parameter) {
    if (!"header".equals(parameter.getIn()) && !"query".equals(parameter.getIn())) return false;
    String name = parameter.getName().toLowerCase(Locale.ROOT);
    return name.equals("authorization") || name.equals("x-api-key") || name.equals("api_key")
        || name.equals("apikey") || name.equals("access_token") || name.equals("token");
  }

  private String describe(Operation operation, String method, String path) {
    if (operation.getSummary() != null && !operation.getSummary().isBlank()) {
      return SchemaTranslator.trim(operation.getSummary());
    }
    if (operation.getDescription() != null && !operation.getDescription().isBlank()) {
      return SchemaTranslator.trim(operation.getDescription());
    }
    return method + " " + path;
  }

  /**
   * Tool names are what the model calls, so they must be stable, legal and unique within a
   * connection. Prefer the document's operationId; fall back to the method and path.
   */
  String uniqueName(String operationId, String method, String path, Set<String> used) {
    String base = operationId != null && !operationId.isBlank()
        ? sanitise(operationId)
        : sanitise(method.toLowerCase(Locale.ROOT) + "_" + path);
    if (base.isBlank()) base = "operation";
    if (base.length() > 60) base = base.substring(0, 60);

    String candidate = base;
    int suffix = 2;
    while (!used.add(candidate)) {
      candidate = base + "_" + suffix++;
    }
    return candidate;
  }

  private String sanitise(String raw) {
    return raw.replaceAll("[{}]", "")
        .replaceAll("[^A-Za-z0-9_]+", "_")
        .replaceAll("_+", "_")
        .replaceAll("^_|_$", "")
        .toLowerCase(Locale.ROOT);
  }
}
