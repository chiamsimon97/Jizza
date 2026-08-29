package com.jizza.workflow.nodes;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.common.autonomy.Decision;
import com.jizza.common.connector.IdempotencyKey;
import com.jizza.common.connector.ToolExecutor;
import com.jizza.common.dto.GeneratedToolDto;
import com.jizza.common.enums.NodeType;
import com.jizza.common.tool.ToolResult;
import com.jizza.common.util.Json;
import com.jizza.persistence.entity.GeneratedToolEntity;
import com.jizza.persistence.repository.GeneratedToolRepository;
import com.jizza.workflow.engine.NodeExecutor;
import com.jizza.workflow.sandbox.JexlSandbox;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Calls a generated connector tool from inside a workflow (R31).
 *
 * <p>The idempotency key is {@code run:<runId>:<nodeId>} -- stable across retries <em>and</em>
 * across a process restart, which is exactly what makes a side-effecting node exactly-once rather
 * than at-least-once. The autonomy policy is consulted here as well as in the Capability Registry,
 * because a workflow node is autonomous activity in its own right.
 */
@Component
public class ConnectorToolNodeExecutor implements NodeExecutor {

  private final GeneratedToolRepository tools;
  private final ToolExecutor executor;
  private final com.jizza.common.autonomy.AutonomyGate autonomyGate;
  private final JexlSandbox sandbox;

  public ConnectorToolNodeExecutor(GeneratedToolRepository tools, ToolExecutor executor,
                                   com.jizza.common.autonomy.AutonomyGate autonomyGate,
                                   JexlSandbox sandbox) {
    this.tools = tools;
    this.executor = executor;
    this.autonomyGate = autonomyGate;
    this.sandbox = sandbox;
  }

  @Override
  public NodeType type() { return NodeType.CONNECTOR_TOOL; }

  @Override
  public Outcome execute(Execution execution) {
    JsonNode config = execution.node().config();
    if (config == null || !config.hasNonNull("toolId")) {
      return Outcome.permanentFailure("This node does not say which tool to call");
    }

    UUID toolId;
    try {
      toolId = UUID.fromString(config.path("toolId").asText());
    } catch (IllegalArgumentException e) {
      return Outcome.permanentFailure("'toolId' is not a tool id");
    }

    GeneratedToolEntity entity = tools.findById(toolId).orElse(null);
    if (entity == null) {
      return Outcome.permanentFailure("The tool this node calls no longer exists");
    }
    if (!entity.isEnabled()) {
      return Outcome.permanentFailure("The tool '" + entity.getName() + "' is disabled");
    }

    JsonNode args;
    try {
      args = resolveArguments(config.path("arguments"), execution);
    } catch (RuntimeException e) {
      return Outcome.permanentFailure("The node's arguments could not be prepared: "
          + e.getMessage());
    }

    if (execution.owningAgentId() != null) {
      Decision decision = autonomyGate.evaluate(execution.owningAgentId(), entity.getName(), args);
      if (decision.denied()) {
        return Outcome.permanentFailure("Blocked by autonomy policy: " + decision.reason());
      }
      if (decision.needsPermission()) {
        return Outcome.awaitPermission(
            "This step needs your approval before it runs " + entity.getName() + ".");
      }
    }

    IdempotencyKey key = entity.isSideEffecting()
        ? IdempotencyKey.of(execution.userId(), "run:" + execution.runId() + ":"
            + execution.node().id())
        : null;

    GeneratedToolDto dto = new GeneratedToolDto(entity.getId(), entity.getConnectionId(),
        entity.getName(), entity.getDescription(), entity.getInputSchema(), entity.getHttpMethod(),
        entity.getPathTemplate(), entity.getParamMapping(), entity.getAuthMapping(),
        entity.isSideEffecting(), entity.isEnabled());

    ToolResult result = executor.execute(dto, args, key);
    if (result.ok()) return Outcome.completed(result.output());

    // A transport or upstream problem is worth retrying; a rejected request is not.
    return isRetryable(result.error())
        ? Outcome.retryable(result.error())
        : Outcome.permanentFailure(result.error());
  }

  /**
   * Argument values may be literals or {@code {"expr": "..."}} objects evaluated in the sandbox
   * against earlier node outputs.
   */
  private JsonNode resolveArguments(JsonNode template, Execution execution) {
    if (template == null || template.isNull()) return Json.obj();
    if (template.isObject() && template.hasNonNull("expr")) {
      return sandbox.evaluate(template.path("expr").asText(), execution.context().variables());
    }
    if (template.isObject()) {
      var out = Json.obj();
      Iterator<Map.Entry<String, JsonNode>> fields = template.fields();
      while (fields.hasNext()) {
        var field = fields.next();
        out.set(field.getKey(), resolveArguments(field.getValue(), execution));
      }
      return out;
    }
    if (template.isArray()) {
      var out = Json.mapper().createArrayNode();
      for (JsonNode item : template) out.add(resolveArguments(item, execution));
      return out;
    }
    return template;
  }

  private boolean isRetryable(String error) {
    if (error == null) return true;
    String lower = error.toLowerCase(java.util.Locale.ROOT);
    if (lower.contains("rate limiting")) return true;
    if (lower.contains("could not reach") || lower.contains("interrupted")) return true;
    if (lower.contains("replied 5")) return true;
    // Blocked egress, a 4xx, a disabled tool or a missing credential will not fix itself.
    return false;
  }
}
