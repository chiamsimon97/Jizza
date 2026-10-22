package com.jizza.engine.workflow.nodes;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.autonomy.Decision;
import com.jizza.engine.api.autonomy.GuardrailContext;
import com.jizza.engine.api.connector.IdempotencyKey;
import com.jizza.engine.api.connector.ToolExecutor;
import com.jizza.engine.api.dto.GeneratedToolDto;
import com.jizza.engine.api.enums.NodeType;
import com.jizza.engine.api.tool.ToolResult;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.persistence.entity.GeneratedToolEntity;
import com.jizza.engine.persistence.repository.GeneratedToolRepository;
import com.jizza.engine.workflow.engine.NodeExecutor;
import com.jizza.engine.workflow.sandbox.JexlSandbox;
import com.jizza.engine.workflow.sandbox.Templates;
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
  private final com.jizza.engine.api.autonomy.AutonomyGate autonomyGate;
  private final JexlSandbox sandbox;

  public ConnectorToolNodeExecutor(GeneratedToolRepository tools, ToolExecutor executor,
                                   com.jizza.engine.api.autonomy.AutonomyGate autonomyGate,
                                   JexlSandbox sandbox) {
    this.tools = tools;
    this.executor = executor;
    this.autonomyGate = autonomyGate;
    this.sandbox = sandbox;
  }

  private org.springframework.beans.factory.ObjectProvider<com.jizza.engine.api.authz.PermissionEvaluator> permissions;
  private com.jizza.engine.persistence.repository.ConnectionRepository connections;

  /**
   * Account permissions: this node calls the executor directly rather than through the Capability
   * Registry, so it repeats the registry's check that the run's owner may use the connector in the
   * account that owns it. Optional so the engine harness can run without access control.
   */
  @org.springframework.beans.factory.annotation.Autowired
  public void setAccessControl(
      org.springframework.beans.factory.ObjectProvider<com.jizza.engine.api.authz.PermissionEvaluator> permissions,
      com.jizza.engine.persistence.repository.ConnectionRepository connections) {
    this.permissions = permissions;
    this.connections = connections;
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

    if (!accountAllows(execution.userId(), entity.getConnectionId())) {
      return Outcome.permanentFailure("You don't have access to the connector behind '"
          + entity.getName() + "'");
    }

    JsonNode args;
    try {
      args = resolveArguments(config.path("arguments"), execution);
    } catch (RuntimeException e) {
      return Outcome.permanentFailure("The node's arguments could not be prepared: "
          + e.getMessage());
    }

    // Every connector step is judged by the guardrails, whoever owns the workflow. One with no
    // owning agent runs as Jizza, so it can never slip past the rules by having no agent.
    UUID actor = execution.owningAgentId() != null ? execution.owningAgentId()
        : orchestratorOf(execution.userId());
    if (actor == null) {
      return Outcome.permanentFailure("No agent is available to run " + entity.getName()
          + " under your guardrails");
    }
    GuardrailContext guardrail = execution.workflowId() == null
        ? new GuardrailContext(actor, entity.getName(), args, null, null, null, false,
            entity.effectiveActionType())
        : GuardrailContext.inWorkflow(actor, entity.getName(), args, execution.workflowId(),
            execution.workflowVersion(), execution.node().id(), entity.effectiveActionType());
    guardrail = guardrail.withConnection(entity.getConnectionId());
    boolean approved = execution.context().approved(execution.node().id());
    // Once the user approved this step, a second evaluation would only ask the same question
    // again; a never rule still stops it, and nothing is spent from Jizza's say or a budget.
    Decision decision = approved ? autonomyGate.evaluate(guardrail)
        : autonomyGate.decide(guardrail);
    if (decision.denied()) {
      return Outcome.permanentFailure("Blocked by your guardrails: " + decision.reason());
    }
    if (!approved && (decision.needsPermission() || decision.needsJizza())) {
      return Outcome.awaitPermission(
          "This step needs your approval before it runs " + entity.getName() + ". "
              + decision.reason() + ".");
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

  private com.jizza.engine.persistence.repository.AgentRepository agents;

  /** Optional so the engine harness can run without agents; without it an agentless step fails. */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setAgents(com.jizza.engine.persistence.repository.AgentRepository agents) {
    this.agents = agents;
  }

  private UUID orchestratorOf(UUID userId) {
    if (agents == null || userId == null) return null;
    return agents.findByUserIdAndIsOrchestratorTrue(userId)
        .map(com.jizza.engine.persistence.entity.AgentEntity::getId).orElse(null);
  }

  private boolean accountAllows(UUID userId, UUID connectionId) {
    if (permissions == null || connections == null) return true;
    var evaluator = permissions.getIfAvailable();
    if (evaluator == null) return true;
    return connections.findById(connectionId)
        .map(c -> evaluator.isAllowed(userId, c.getWorkspaceId(),
            com.jizza.engine.api.authz.Permissions.CONNECTOR_VIEW))
        .orElse(false);
  }

  /**
   * Argument values may be literals, text with {@code {{ }}} placeholders (the run's input and
   * earlier steps' results, as in every other built-in step), or {@code {"expr": "..."}} objects
   * evaluated in the sandbox against earlier node outputs.
   */
  private JsonNode resolveArguments(JsonNode template, Execution execution) {
    if (template == null || template.isNull()) return Json.obj();
    if (template.isTextual() && template.asText().contains("{{")) {
      return Templates.renderValue(template.asText(), sandbox, execution.context().variables());
    }
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
