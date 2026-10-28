package com.jizza.engine.devices.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.authz.Permissions;
import com.jizza.engine.api.autonomy.ActionTypeSource;
import com.jizza.engine.api.device.DeviceActionCatalog;
import com.jizza.engine.api.device.DeviceActionSpec;
import com.jizza.engine.api.device.DeviceCommandDto;
import com.jizza.engine.api.device.DeviceCommandRequest;
import com.jizza.engine.api.device.DeviceCommandStatus;
import com.jizza.engine.api.device.DeviceTarget;
import com.jizza.engine.api.enums.ActionType;
import com.jizza.engine.api.error.JizzaException;
import com.jizza.engine.api.tool.ToolCall;
import com.jizza.engine.api.tool.ToolProvider;
import com.jizza.engine.api.tool.ToolResult;
import com.jizza.engine.api.tool.ToolSpec;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.devices.broker.CommandBroker;
import com.jizza.engine.persistence.entity.AgentEntity;
import com.jizza.engine.persistence.entity.DeviceEntity;
import com.jizza.engine.persistence.repository.AgentRepository;
import com.jizza.engine.persistence.repository.DeviceRepository;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The person's paired devices as tools for Jizza in chat: {@code device_<name>__<action>}, one per
 * action each device declared, so the guardrails can judge each action by what it does.
 *
 * <p>Only the orchestrator gets them, as only it gets new MCP servers, and only for devices the
 * person paired themselves. The Capability Registry still runs every call (kill switch, schema,
 * {@code device_use} in the device's account, guardrails, audit); this provider only routes the
 * call to the broker and waits a short while for the device's answer.
 */
@Component
public class DeviceToolProvider implements ToolProvider, ActionTypeSource {

  static final String PREFIX = "device_";
  private static final int MAX_NAME = 64;
  /** How long a chat turn waits for a device before saying the result will come later. */
  static final Duration CHAT_WAIT = Duration.ofSeconds(60);

  private final AgentRepository agents;
  private final DeviceRepository devices;
  private final DeviceActionCatalog catalog;
  private final CommandBroker broker;

  public DeviceToolProvider(AgentRepository agents, DeviceRepository devices,
                            DeviceActionCatalog catalog, CommandBroker broker) {
    this.agents = agents;
    this.devices = devices;
    this.catalog = catalog;
    this.broker = broker;
  }

  record Exposed(String name, DeviceEntity device, DeviceActionSpec spec) {}

  @Override
  public List<ToolSpec> toolsFor(UUID agentId) {
    return exposed(agentId).values().stream().map(e -> new ToolSpec(e.name(),
        e.spec().title() + " on " + e.device().getName() + " ("
            + e.device().getPlatform() + "). " + (e.spec().description() == null ? ""
            : e.spec().description()),
        e.spec().input() == null ? Json.obj().put("type", "object") : e.spec().input(),
        e.spec().actionType().sideEffecting())).toList();
  }

  @Override
  public boolean supports(UUID agentId, String toolName) {
    return toolName != null && toolName.startsWith(PREFIX) && exposed(agentId).containsKey(toolName);
  }

  @Override
  public ToolResult invoke(UUID agentId, ToolCall call) {
    Exposed e = exposed(agentId).get(call.name());
    if (e == null) return ToolResult.error("Tool '" + call.name() + "' is not available to this agent");
    AgentEntity agent = agents.findById(agentId).orElse(null);
    if (agent == null) return ToolResult.error("The agent is gone");
    DeviceCommandDto cmd;
    try {
      cmd = broker.submit(new DeviceCommandRequest(agent.getUserId(), e.device().getWorkspaceId(),
          DeviceTarget.device(e.device().getId()), e.spec().action(), 0, call.args(), null, null,
          null, null, true));
    } catch (JizzaException ex) {
      return ToolResult.error(ex.getMessage());
    }
    DeviceCommandDto done = broker.await(cmd.id(), CHAT_WAIT);
    if (done.status() == DeviceCommandStatus.SUCCEEDED) {
      return ToolResult.ok(done.output() == null ? Json.obj() : done.output());
    }
    if (done.status().finished()) return ToolResult.error(done.explain());
    ObjectNode pending = Json.obj().put("status", "waiting")
        .put("message", done.explain() + ". It keeps running; the result will appear on the "
            + "device's page under Settings > Devices.")
        .put("commandId", done.id().toString());
    return ToolResult.ok(pending);
  }

  /** The device's account, so the registry requires device_use there before the call runs. */
  @Override
  public Optional<UUID> workspaceOf(UUID agentId, String toolName) {
    Exposed e = exposed(agentId).get(toolName);
    return e == null ? Optional.empty() : Optional.of(e.device().getWorkspaceId());
  }

  @Override
  public String requiredPermission(String toolName) {
    return Permissions.DEVICE_USE;
  }

  @Override
  public Optional<ActionType> actionTypeOf(UUID agentId, String toolRef) {
    if (toolRef == null || !toolRef.startsWith(PREFIX)) return Optional.empty();
    Exposed e = exposed(agentId).get(toolRef);
    return e == null ? Optional.empty() : Optional.of(e.spec().actionType());
  }

  /** Browser actions that do more than read always ask in chat; reading may run within the rules. */
  @Override
  public boolean alwaysAsks(UUID agentId, String toolRef) {
    if (toolRef == null || !toolRef.startsWith(PREFIX)) return false;
    Exposed e = exposed(agentId).get(toolRef);
    return e != null && e.spec().action().startsWith("browser.")
        && e.spec().actionType() != ActionType.READ;
  }

  private Map<String, Exposed> exposed(UUID agentId) {
    Map<String, Exposed> out = new LinkedHashMap<>();
    AgentEntity agent = agentId == null ? null : agents.findById(agentId).orElse(null);
    if (agent == null || !agent.isOrchestrator()) return out;
    Set<String> slugs = new HashSet<>();
    for (DeviceEntity device : devices.findByOwnerUserIdAndStatusOrderByCreatedAtAsc(
        agent.getUserId(), "active")) {
      String slug = slug(device.getName());
      if (!slugs.add(slug)) {
        slug = slug + device.getId().toString().substring(0, 4);
        slugs.add(slug);
      }
      List<String> blocked = new ArrayList<>();
      if (device.getBlockedActions() != null) device.getBlockedActions().forEach(b -> blocked.add(b.asText()));
      if (device.getActions() == null) continue;
      for (JsonNode ref : device.getActions()) {
        String action = ref.asText().split("@")[0];
        if (blocked.contains(action)) continue;
        Optional<DeviceActionSpec> spec = catalog.resolve(action, 0);
        if (spec.isEmpty() || (spec.get().shell() && !device.isShellEnabled())) continue;
        String name = name(slug, action);
        out.putIfAbsent(name, new Exposed(name, device, spec.get()));
      }
    }
    return out;
  }

  static String name(String slug, String action) {
    String act = action.replaceAll("[^A-Za-z0-9]", "_");
    int room = MAX_NAME - PREFIX.length() - 2 - act.length();
    String s = slug.length() > Math.max(1, room) ? slug.substring(0, Math.max(1, room)) : slug;
    return PREFIX + s + "__" + act;
  }

  static String slug(String name) {
    String s = name == null ? "" : name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_")
        .replaceAll("^_+|_+$", "");
    return s.isEmpty() ? "device" : s;
  }
}
