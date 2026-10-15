package com.jizza.engine.agents.registry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.agent.ApprovedCall;
import com.jizza.engine.api.dto.NotificationDto;
import com.jizza.engine.api.enums.NotificationStatus;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.activity.Activity;
import com.jizza.engine.api.activity.ActivityTypes;
import com.jizza.engine.api.activity.JobCancelledException;
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
    return execute(agentId, call, autonomous, null);
  }

  @Override
  public ApprovedCall runApproved(UUID userId, UUID notificationId) {
    NotificationBus bus = notifications.getIfAvailable();
    if (bus == null) throw new IllegalStateException("The notification bus is unavailable");
    NotificationDto request = bus.get(notificationId);
    if (request == null || !userId.equals(request.userId())
        || request.kind() != NotificationKind.PERMISSION_REQUEST) {
      throw new NotFoundException("Permission request", notificationId);
    }
    if (request.status() != NotificationStatus.APPROVED) {
      throw new ValidationException("That request has not been approved");
    }
    String tool = request.context() == null ? null : request.context().path("tool").asText(null);
    if (request.sourceAgentId() == null || tool == null) {
      throw new ValidationException("That request does not hold a call to run");
    }
    JsonNode args = request.context().path("args");
    // The call id doubles as the idempotency reference, so a side-effecting call runs once per
    // approval however many times this is reached.
    ToolCall call = new ToolCall("approval:" + notificationId, tool,
        args.isObject() ? args.deepCopy() : Json.obj());
    ToolResult result = execute(request.sourceAgentId(), call, false, notificationId);
    return new ApprovedCall(notificationId, request.sourceAgentId(), request.sourceAgentName(), tool,
        result);
  }

  private ToolResult execute(UUID agentId, ToolCall call, boolean autonomous, UUID approvedBy) {
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
    if (decision.needsPermission() && approvedBy == null) {
      return requestPermission(agent, call, decision, provider.redactArguments(call.name()));
    }

    ObjectNode detail = Json.obj().put("autonomous", autonomous)
        .put("decision", approvedBy != null ? "APPROVED" : decision.rule().name());
    if (approvedBy != null) detail.put("approval", approvedBy.toString());
    audit.record(agent.getUserId(), ActorType.AGENT, agentId, "tool.call", call.name(),
        detail.set("args", provider.redactArguments(call.name())
            ? Json.obj().put("redacted", true) : call.args()));

    boolean shown = shownInActivity(call.name());
    String stepId = call.id() != null ? call.id() : UUID.randomUUID().toString();
    if (shown) {
      Activity.emit(ActivityTypes.TOOL_CALL, agentId, agent.getName(), p -> p.put("id", stepId)
          .put("tool", call.name()).put("label", label(call.name())).put("state", "started"));
    }
    long started = System.nanoTime();
    ToolResult result;
    try {
      result = provider.invoke(agentId, call);
    } catch (JobCancelledException e) {
      throw e;
    } catch (RuntimeException e) {
      log.warn("Tool {} failed for agent {}", call.name(), agentId, e);
      result = ToolResult.error(e.getMessage());
    }
    if (shown) {
      long ms = (System.nanoTime() - started) / 1_000_000;
      ToolResult finished = result;
      Activity.emit(ActivityTypes.TOOL_CALL, agentId, agent.getName(), p -> {
        p.put("id", stepId).put("tool", call.name()).put("label", label(call.name()))
            .put("state", finished.ok() ? "done" : "failed").put("ms", ms);
        if (!finished.ok()) p.put("error", abbreviate(finished.error()));
      });
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

  /**
   * Delegation is shown as the agents' own conversation, and app-control steps as UI actions, so
   * listing them again as tool calls would only repeat the timeline.
   */
  private static boolean shownInActivity(String toolName) {
    return !toolName.startsWith("delegate_to_") && !toolName.startsWith("app_");
  }

  /** "zerodha_get_holdings" reads as "Zerodha get holdings". */
  static String label(String toolName) {
    String words = toolName.replace('_', ' ').replace('-', ' ').trim();
    return words.isEmpty() ? toolName : Character.toUpperCase(words.charAt(0)) + words.substring(1);
  }

  private static String abbreviate(String text) {
    if (text == null) return null;
    return text.length() <= 240 ? text : text.substring(0, 240) + "…";
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
  private ToolResult requestPermission(AgentEntity agent, ToolCall call, Decision decision,
                                       boolean redact) {
    NotificationBus bus = notifications.getIfAvailable();
    audit.record(agent.getUserId(), ActorType.AGENT, agent.getId(), "tool.permission_requested",
        call.name(), Json.obj().put("policy", decision.matchedPolicy())
            .set("args", redact ? Json.obj().put("redacted", true) : call.args()));

    if (bus == null) {
      return ToolResult.error("Tool '" + call.name() + "' needs your approval, but the notification "
          + "bus is unavailable, so nothing was run.");
    }
    JsonNode detail = Json.obj().put("tool", call.name()).set("args", call.args());
    var notification = bus.raise(NotificationKind.PERMISSION_REQUEST, agent.getId(),
        new NotificationContext(agent.getUserId(),
            agent.getName() + " needs approval to run " + call.name(), detail,
            null, null, null, null));
    Activity.emit(ActivityTypes.APPROVAL_REQUIRED, agent.getId(), agent.getName(), p -> p
        .put("notificationId", notification.id().toString()).put("tool", call.name())
        .put("label", label(call.name())).put("reason", decision.reason()));
    return ToolResult.error(PERMISSION_REQUIRED + notification.id());
  }
}
