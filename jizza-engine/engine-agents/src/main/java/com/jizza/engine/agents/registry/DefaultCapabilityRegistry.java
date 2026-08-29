package com.jizza.engine.agents.registry;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.agent.CapabilityRegistry;
import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.autonomy.AutonomyGate;
import com.jizza.engine.api.autonomy.Decision;
import com.jizza.engine.api.autonomy.KillSwitch;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.enums.CapabilityType;
import com.jizza.engine.api.enums.NotificationKind;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.notification.NotificationBus;
import com.jizza.engine.api.notification.NotificationContext;
import com.jizza.engine.api.tool.ToolCall;
import com.jizza.engine.api.tool.ToolProvider;
import com.jizza.engine.api.tool.ToolResult;
import com.jizza.engine.api.tool.ToolSchemaValidator;
import com.jizza.engine.api.tool.ToolSpec;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.persistence.entity.AgentCapabilityEntity;
import com.jizza.engine.persistence.entity.AgentEntity;
import com.jizza.engine.persistence.repository.AgentCapabilityRepository;
import com.jizza.engine.persistence.repository.AgentRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single choke point for tool execution (R28). Nothing runs until, in order:
 * the agent is enabled, the kill switch is off, the call matches its declared schema, and the
 * autonomy policy allows it. Every attempt is audited whether or not it proceeds.
 *
 * <p>A tool the model asked for but that this agent does not hold is not merely refused, it was
 * never offered: {@link #toolsFor} is the agent's allow-list.
 */
@Service
public class DefaultCapabilityRegistry implements CapabilityRegistry {

  private static final Logger log = LoggerFactory.getLogger(DefaultCapabilityRegistry.class);

  /** Prefix a caller can look for to tell "blocked pending approval" from an ordinary failure. */
  public static final String PERMISSION_REQUIRED = "permission_required:";

  private final ObjectProvider<ToolProvider> providers;
  private final AgentRepository agents;
  private final AgentCapabilityRepository capabilities;
  private final AutonomyGate autonomyGate;
  private final KillSwitch killSwitch;
  private final AuditLog audit;
  private final ObjectProvider<NotificationBus> notifications;

  public DefaultCapabilityRegistry(ObjectProvider<ToolProvider> providers, AgentRepository agents,
                                   AgentCapabilityRepository capabilities, AutonomyGate autonomyGate,
                                   KillSwitch killSwitch, AuditLog audit,
                                   ObjectProvider<NotificationBus> notifications) {
    this.providers = providers;
    this.agents = agents;
    this.capabilities = capabilities;
    this.autonomyGate = autonomyGate;
    this.killSwitch = killSwitch;
    this.audit = audit;
    this.notifications = notifications;
  }

  @Override
  public List<ToolSpec> toolsFor(UUID agentId) {
    Map<String, ToolSpec> byName = new LinkedHashMap<>();
    providers.forEach(provider -> {
      for (ToolSpec spec : provider.toolsFor(agentId)) byName.putIfAbsent(spec.name(), spec);
    });
    // A denied tool is withheld entirely rather than offered and then refused: the model should
    // not be able to plan around a capability it will never be granted.
    List<ToolSpec> visible = new ArrayList<>();
    for (ToolSpec spec : byName.values()) {
      if (!autonomyGate.evaluate(agentId, spec.name(), null).denied()) visible.add(spec);
    }
    return visible;
  }

  @Override
  public ToolResult invoke(UUID agentId, ToolCall call) {
    return invoke(agentId, call, false);
  }

  @Override
  public ToolResult invoke(UUID agentId, ToolCall call, boolean autonomous) {
    AgentEntity agent = agents.findById(agentId)
        .orElseThrow(() -> new NotFoundException("Agent", agentId));

    if (!agent.isEnabled() || !autonomyGate.agentEnabled(agentId)) {
      return refuse(agent, call, "agent-disabled", "This agent is currently disabled");
    }
    if (autonomous && killSwitch.paused(agent.getUserId())) {
      return refuse(agent, call, "kill-switch",
          "Autonomy is paused by the kill switch; nothing runs unattended until it is released");
    }

    ToolProvider provider = providerFor(agentId, call.name());
    if (provider == null) {
      return refuse(agent, call, "not-granted",
          "Tool '" + call.name() + "' is not assigned to this agent");
    }

    ToolSpec spec = specFor(provider, agentId, call.name());
    if (spec != null) {
      var validation = ToolSchemaValidator.validate(spec.inputSchema(), call.args());
      if (!validation.valid()) {
        return refuse(agent, call, "schema", "Arguments did not match the tool schema: "
            + validation.message());
      }
    }

    Decision decision = autonomyGate.evaluate(agentId, call.name(), call.args());
    if (decision.denied()) {
      return refuse(agent, call, "policy-deny", decision.reason());
    }
    if (decision.needsPermission()) {
      return requestPermission(agent, call, decision);
    }

    audit.record(agent.getUserId(), ActorType.AGENT, agentId, "tool.call", call.name(),
        Json.obj().put("autonomous", autonomous)
            .put("decision", decision.rule().name())
            .set("args", call.args()));

    ToolResult result;
    try {
      result = provider.invoke(agentId, call);
    } catch (RuntimeException e) {
      log.warn("Tool {} failed for agent {}", call.name(), agentId, e);
      result = ToolResult.error(e.getMessage());
    }

    audit.record(agent.getUserId(), ActorType.AGENT, agentId,
        result.ok() ? "tool.result.ok" : "tool.result.error", call.name(),
        result.ok() ? null : Json.obj().put("error", result.error()));
    return result;
  }

  @Override
  @Transactional
  public void grant(UUID agentId, CapabilityType type, UUID capabilityRef) {
    if (capabilities.findByAgentIdAndCapabilityTypeAndCapabilityRef(agentId, type, capabilityRef)
        .isPresent()) {
      return;
    }
    AgentCapabilityEntity entity = new AgentCapabilityEntity();
    entity.setAgentId(agentId);
    entity.setCapabilityType(type);
    entity.setCapabilityRef(capabilityRef);
    capabilities.save(entity);

    agents.findById(agentId).ifPresent(agent -> audit.record(agent.getUserId(), ActorType.USER, null,
        "capability.granted", type + ":" + capabilityRef,
        Json.obj().put("agentId", agentId.toString())));
  }

  @Override
  @Transactional
  public void revoke(UUID agentId, CapabilityType type, UUID capabilityRef) {
    capabilities.deleteByAgentIdAndCapabilityTypeAndCapabilityRef(agentId, type, capabilityRef);
    agents.findById(agentId).ifPresent(agent -> audit.record(agent.getUserId(), ActorType.USER, null,
        "capability.revoked", type + ":" + capabilityRef,
        Json.obj().put("agentId", agentId.toString())));
  }

  @Override
  public boolean hasCapability(UUID agentId, String toolName) {
    return providerFor(agentId, toolName) != null;
  }

  private ToolProvider providerFor(UUID agentId, String toolName) {
    for (ToolProvider provider : providers) {
      if (provider.supports(agentId, toolName)) return provider;
    }
    return null;
  }

  private ToolSpec specFor(ToolProvider provider, UUID agentId, String toolName) {
    return provider.toolsFor(agentId).stream()
        .filter(s -> s.name().equals(toolName)).findFirst().orElse(null);
  }

  private ToolResult refuse(AgentEntity agent, ToolCall call, String reasonCode, String message) {
    audit.record(agent.getUserId(), ActorType.AGENT, agent.getId(), "tool.blocked", call.name(),
        Json.obj().put("reason", reasonCode).put("message", message));
    return ToolResult.error(message);
  }

  /**
   * An {@code ask} rule does not execute and does not silently drop: it raises a permission request
   * attributed to this agent and reports back that the work is waiting on the user (R37/R41).
   */
  private ToolResult requestPermission(AgentEntity agent, ToolCall call, Decision decision) {
    NotificationBus bus = notifications.getIfAvailable();
    audit.record(agent.getUserId(), ActorType.AGENT, agent.getId(), "tool.permission_requested",
        call.name(), Json.obj().put("policy", decision.matchedPolicy()).set("args", call.args()));

    if (bus == null) {
      return ToolResult.error("Tool '" + call.name() + "' needs your approval, but the notification "
          + "bus is unavailable, so nothing was run.");
    }
    JsonNode detail = Json.obj().put("tool", call.name()).set("args", call.args());
    var notification = bus.raise(NotificationKind.PERMISSION_REQUEST, agent.getId(),
        new NotificationContext(agent.getUserId(),
            agent.getName() + " needs approval to run " + call.name(), detail,
            null, null, null, null));
    return ToolResult.error(PERMISSION_REQUIRED + notification.id());
  }
}
