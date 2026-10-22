package com.jizza.engine.workflow.nodes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.authz.PermissionEvaluator;
import com.jizza.engine.api.authz.Permissions;
import com.jizza.engine.api.autonomy.AutonomyGate;
import com.jizza.engine.api.autonomy.Decision;
import com.jizza.engine.api.autonomy.GuardrailContext;
import com.jizza.engine.api.enums.NodeType;
import com.jizza.engine.api.error.SandboxViolationException;
import com.jizza.engine.api.mcp.McpServerDto;
import com.jizza.engine.api.mcp.McpServers;
import com.jizza.engine.api.mcp.McpToolDto;
import com.jizza.engine.api.tool.ToolResult;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.persistence.entity.AgentEntity;
import com.jizza.engine.persistence.repository.AgentRepository;
import com.jizza.engine.workflow.engine.NodeExecutor;
import com.jizza.engine.workflow.sandbox.JexlSandbox;
import com.jizza.engine.workflow.sandbox.Templates;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Calls one tool of an MCP server added under Connectors: {@code serverId}, {@code tool} (the
 * server's own name for it) and {@code arguments}, whose text values are templates, so a value that
 * is a single {@code {{ }}} placeholder keeps its type.
 *
 * <p>It is judged as a service action is: the run's owner needs connector access in the server's
 * account, the tool must still be on, and the guardrails decide with what the user said the tool
 * does. A tool nobody classified is judged as the most consequential kind; once a step that asked
 * is approved, it runs once.
 */
@Component
public class McpToolNodeExecutor implements NodeExecutor {

  private final ObjectProvider<McpServers> servers;
  private final AutonomyGate autonomyGate;
  private final JexlSandbox sandbox;
  private final AgentRepository agents;
  private final ObjectProvider<PermissionEvaluator> permissions;

  public McpToolNodeExecutor(ObjectProvider<McpServers> servers, AutonomyGate autonomyGate,
                             JexlSandbox sandbox, AgentRepository agents,
                             ObjectProvider<PermissionEvaluator> permissions) {
    this.servers = servers;
    this.autonomyGate = autonomyGate;
    this.sandbox = sandbox;
    this.agents = agents;
    this.permissions = permissions;
  }

  @Override
  public NodeType type() { return NodeType.MCP_TOOL; }

  @Override
  public Outcome execute(Execution execution) {
    JsonNode config = execution.node().config();
    McpServers store = servers.getIfAvailable();
    if (store == null) return Outcome.permanentFailure("MCP servers are not available here");
    if (config == null || !config.hasNonNull("serverId") || !config.hasNonNull("tool")) {
      return Outcome.permanentFailure("This step does not say which MCP tool to call");
    }
    UUID serverId;
    try {
      serverId = UUID.fromString(config.path("serverId").asText());
    } catch (IllegalArgumentException e) {
      return Outcome.permanentFailure("'serverId' is not a server id");
    }
    String toolName = config.path("tool").asText();

    McpServerDto server = store.find(serverId).orElse(null);
    if (server == null) return Outcome.permanentFailure("The MCP server this step uses was removed");
    if (!accountAllows(execution.userId(), server)) {
      return Outcome.permanentFailure("You don't have access to the MCP server " + server.name());
    }
    if (!server.enabled()) return Outcome.permanentFailure(server.name() + " is turned off");
    McpToolDto tool = server.tools().stream().filter(t -> t.name().equals(toolName)).findFirst()
        .orElse(null);
    if (tool == null) {
      return Outcome.permanentFailure(server.name() + " no longer offers '" + toolName + "'");
    }
    if (!tool.enabled()) {
      return Outcome.permanentFailure("The tool '" + toolName + "' is turned off for " + server.name());
    }

    JsonNode args;
    try {
      args = render(config.path("arguments"), execution.context().variables());
    } catch (SandboxViolationException | IllegalArgumentException e) {
      return Outcome.permanentFailure("The step's arguments could not be prepared: " + e.getMessage());
    }
    if (!args.isObject()) return Outcome.permanentFailure("The step's arguments must be an object");

    // Judged as Jizza when the workflow has no owning agent, so no step slips past the rules.
    UUID actor = execution.owningAgentId() != null ? execution.owningAgentId()
        : agents.findByUserIdAndIsOrchestratorTrue(execution.userId()).map(AgentEntity::getId)
            .orElse(null);
    if (actor == null) {
      return Outcome.permanentFailure("No agent is available to run " + toolName
          + " under your guardrails");
    }
    GuardrailContext guardrail = execution.workflowId() == null
        ? new GuardrailContext(actor, tool.toolName(), args, null, null, null, false,
            tool.actionType())
        : GuardrailContext.inWorkflow(actor, tool.toolName(), args, execution.workflowId(),
            execution.workflowVersion(), execution.node().id(), tool.actionType());
    boolean approved = execution.context().approved(execution.node().id());
    Decision decision = approved ? autonomyGate.evaluate(guardrail) : autonomyGate.decide(guardrail);
    if (decision.denied()) {
      return Outcome.permanentFailure("Blocked by your guardrails: " + decision.reason());
    }
    if (!approved && (decision.needsPermission() || decision.needsJizza())) {
      return Outcome.awaitPermission("This step needs your approval before it runs " + toolName
          + " on " + server.name() + ". " + decision.reason() + ".");
    }

    ToolResult result = store.call(serverId, toolName, args);
    if (result.ok()) return Outcome.completed(result.output());
    return isRetryable(result.error()) ? Outcome.retryable(result.error())
        : Outcome.permanentFailure(result.error());
  }

  private boolean accountAllows(UUID userId, McpServerDto server) {
    PermissionEvaluator evaluator = permissions.getIfAvailable();
    if (evaluator == null) return userId.equals(server.userId());
    return evaluator.isAllowed(userId, server.workspaceId(), Permissions.CONNECTOR_USE);
  }

  /** Text values are templates; everything else is taken as written. */
  private JsonNode render(JsonNode value, Map<String, Object> variables) {
    if (value == null || value.isMissingNode() || value.isNull()) return Json.obj();
    if (value.isTextual()) return Templates.renderValue(value.asText(), sandbox, variables);
    if (value.isObject()) {
      ObjectNode out = Json.obj();
      value.properties().forEach(e -> {
        JsonNode rendered = render(e.getValue(), variables);
        // An empty optional field is left out rather than sent as "".
        if (!(rendered.isTextual() && rendered.asText().isEmpty()) && !rendered.isNull()) {
          out.set(e.getKey(), rendered);
        }
      });
      return out;
    }
    if (value.isArray()) {
      var out = Json.mapper().createArrayNode();
      value.forEach(v -> out.add(render(v, variables)));
      return out;
    }
    return value;
  }

  /** A server that could not be reached may answer next time; a refused call will not. */
  private static boolean isRetryable(String error) {
    if (error == null) return true;
    String lower = error.toLowerCase(Locale.ROOT);
    return lower.contains("could not run that") || lower.contains("timeout")
        || lower.contains("timed out") || lower.contains("connection");
  }
}
