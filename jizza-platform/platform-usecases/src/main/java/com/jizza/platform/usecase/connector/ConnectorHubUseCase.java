package com.jizza.platform.usecase.connector;

import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.authz.Access;
import com.jizza.engine.api.device.DeviceActionSpec;
import com.jizza.engine.api.device.DeviceCommandDto;
import com.jizza.engine.api.device.DeviceDto;
import com.jizza.engine.api.device.DeviceStatus;
import com.jizza.engine.api.device.Devices;
import com.jizza.engine.api.dto.AgentDto;
import com.jizza.engine.api.enums.ActionType;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.mcp.McpServerDto;
import com.jizza.engine.api.mcp.McpServerSettings;
import com.jizza.engine.api.mcp.McpServers;
import com.jizza.engine.api.mcp.McpToolDto;
import com.jizza.engine.api.orchestrator.AgentDirectory;
import com.jizza.engine.api.service.ServiceDefinitions;
import com.jizza.engine.api.service.ServiceModel.Action;
import com.jizza.engine.api.service.ServiceModel.Info;
import com.jizza.engine.api.service.ServiceModel.Run;
import com.jizza.engine.api.util.Json;
import com.jizza.platform.usecase.device.DeviceUseCase;
import com.jizza.platform.usecase.mcp.McpServerUseCase;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Connectors hub: every way Jizza reaches outside itself, as one kind of card. A service built
 * in the app, an MCP server and a paired device each answer the same questions: is it ready, how
 * many tools does it bring and of what type, which agents may use it, and when was it last used.
 *
 * <p>Agents are named by their key everywhere here ({@code "*"} means every agent), as MCP servers
 * and devices already store them. A service grants per action, so giving it to an agent grants every
 * action and taking it away revokes every one; agents left alone keep their own per-action grants.
 */
@Service
public class ConnectorHubUseCase {

  private static final Logger log = LoggerFactory.getLogger(ConnectorHubUseCase.class);

  public static final String SERVICE = "service";
  public static final String MCP = "mcp";
  public static final String DEVICE = "device";
  public static final String EVERY_AGENT = "*";

  /** ready, needs_sign_in, failing, not_set_up, off or offline. */
  public record Card(String kind, UUID id, String name, String detail, String status,
                     String problem, int toolCount, Types types, List<String> agentKeys,
                     boolean partial, Instant lastUsedAt, String path) {}

  /** How many of its tools only read, change something, move money, or have no type yet. */
  public record Types(int read, int change, int money, int unset) {
    static Types of(Collection<ActionType> types) {
      int read = 0, change = 0, money = 0, unset = 0;
      for (ActionType t : types) {
        if (t == null) unset++;
        else if (t == ActionType.MONEY) money++;
        else if (t.sideEffecting()) change++;
        else read++;
      }
      return new Types(read, change, money, unset);
    }
  }

  private final ServiceDefinitions services;
  private final ConnectorServiceUseCase serviceUseCase;
  private final McpServers mcpServers;
  private final McpServerUseCase mcpUseCase;
  private final Devices devices;
  private final DeviceUseCase deviceUseCase;
  private final AgentDirectory agents;
  private final AuditLog audit;

  public ConnectorHubUseCase(ServiceDefinitions services, ConnectorServiceUseCase serviceUseCase,
                             McpServers mcpServers, McpServerUseCase mcpUseCase, Devices devices,
                             DeviceUseCase deviceUseCase, AgentDirectory agents, AuditLog audit) {
    this.services = services;
    this.serviceUseCase = serviceUseCase;
    this.mcpServers = mcpServers;
    this.mcpUseCase = mcpUseCase;
    this.devices = devices;
    this.deviceUseCase = deviceUseCase;
    this.agents = agents;
    this.audit = audit;
  }

  /** Every connector the person can see, services first, then MCP servers, then devices. */
  public List<Card> cards(Access access) {
    Map<UUID, String> keys = agentKeysById(access);
    List<Card> cards = new ArrayList<>();
    for (Info info : serviceUseCase.list(access)) cards.add(serviceCard(info, keys));
    for (McpServerDto server : mcpUseCase.list(access)) cards.add(mcpCard(server));
    List<DeviceActionSpec> catalog = deviceUseCase.catalog();
    for (DeviceDto device : deviceUseCase.list(access)) {
      if (device.status() == DeviceStatus.REVOKED) continue;
      cards.add(deviceCard(access, device, catalog));
    }
    return cards;
  }

  /** Sets exactly which agents may use a connector, by key; {@code "*"} gives it to every agent. */
  @Transactional
  public Card setAgents(Access access, String kind, UUID id, List<String> requested) {
    List<String> wanted = clean(access, requested);
    Card card = switch (kind == null ? "" : kind) {
      case SERVICE -> {
        serviceAgents(access, id, wanted);
        yield serviceCard(serviceUseCase.detail(access, id).service(), agentKeysById(access));
      }
      case MCP -> mcpCard(mcpUseCase.update(access, id, new McpServerSettings(null, null, null,
          null, null, null, wanted, null, null, null, null)));
      case DEVICE -> {
        DeviceDto device = deviceUseCase.get(access, id);
        devices.setAgentKeys(device.id(), wanted);
        yield deviceCard(access, deviceUseCase.get(access, id), deviceUseCase.catalog());
      }
      default -> throw new ValidationException("There is no connector of that kind");
    };
    audit.record(access.userId(), ActorType.USER, null, "connector.agents", card.name(),
        Json.obj().put("kind", kind).put("id", id.toString())
            .set("agents", Json.toNode(wanted)));
    return card;
  }

  // ── Services ──────────────────────────────────────────────────────────────

  private Card serviceCard(Info info, Map<UUID, String> keys) {
    List<Action> actions = services.actions(info.id()).stream().filter(Action::enabled).toList();
    Set<String> granted = new LinkedHashSet<>();
    boolean partial = false;
    for (Action action : actions) {
      for (UUID agentId : action.agentIds()) {
        String key = keys.get(agentId);
        if (key != null) granted.add(key);
      }
    }
    for (String key : granted) {
      UUID agentId = idOf(keys, key);
      if (actions.stream().anyMatch(a -> !a.agentIds().contains(agentId))) partial = true;
    }
    String status = switch (info.connectionStatus() == null
        ? com.jizza.engine.api.enums.ConnectionStatus.NEW : info.connectionStatus()) {
      case CONNECTED -> "ready";
      case NEEDS_REAUTH -> "needs_sign_in";
      case ERROR -> "failing";
      case NEW -> "not_set_up";
    };
    Instant lastUsed = null;
    try {
      lastUsed = services.runs(info.id(), 1).stream().map(Run::createdAt).findFirst().orElse(null);
    } catch (RuntimeException e) {
      log.debug("Could not read when service {} was last used", info.id(), e);
    }
    return new Card(SERVICE, info.id(), info.name(),
        info.description() == null || info.description().isBlank() ? info.category()
            : info.description(),
        status, null, actions.size(),
        Types.of(actions.stream().map(a -> a.sideEffecting() ? ActionType.UPDATE : ActionType.READ)
            .toList()),
        List.copyOf(granted), partial, lastUsed, "/connectors/" + info.id());
  }

  /** Gives every action to agents newly named, and takes every one from agents no longer named. */
  private void serviceAgents(Access access, UUID serviceId, List<String> wanted) {
    Map<UUID, String> keys = agentKeysById(access);
    List<Action> actions = services.actions(serviceId);
    Set<UUID> before = new LinkedHashSet<>();
    actions.forEach(a -> a.agentIds().stream().filter(keys::containsKey).forEach(before::add));
    Set<UUID> after = new LinkedHashSet<>();
    if (wanted.contains(EVERY_AGENT)) after.addAll(keys.keySet());
    else wanted.forEach(k -> {
      UUID agentId = idOf(keys, k);
      if (agentId != null) after.add(agentId);
    });
    for (Action action : actions) {
      Set<UUID> next = new LinkedHashSet<>(action.agentIds());
      for (UUID agentId : after) if (!before.contains(agentId)) next.add(agentId);
      for (UUID agentId : before) if (!after.contains(agentId)) next.remove(agentId);
      if (!next.equals(new LinkedHashSet<>(action.agentIds()))) {
        serviceUseCase.assignAgents(access, serviceId, action.id(), List.copyOf(next));
      }
    }
  }

  // ── MCP servers ───────────────────────────────────────────────────────────

  private Card mcpCard(McpServerDto server) {
    List<McpToolDto> tools = server.tools() == null ? List.of()
        : server.tools().stream().filter(McpToolDto::enabled).toList();
    String status;
    String problem = null;
    if (!server.enabled()) {
      status = "off";
    } else if ("needs_auth".equals(server.status())) {
      status = "needs_sign_in";
    } else if ("error".equals(server.status())) {
      status = "failing";
      problem = server.lastError();
    } else if (server.signInTool() != null && server.sessionSignInAt() == null) {
      status = "needs_sign_in";
      problem = "Sign in again after Jizza restarted.";
    } else if ("new".equals(server.status())) {
      status = "not_set_up";
    } else {
      status = "ready";
    }
    Instant lastUsed = null;
    try {
      lastUsed = mcpServers.lastUsedAt(server.id());
    } catch (RuntimeException e) {
      log.warn("Could not read when MCP server {} was last used", server.id(), e);
    }
    return new Card(MCP, server.id(), server.name(), server.url(), status, problem, tools.size(),
        Types.of(tools.stream().map(McpToolDto::actionType).toList()),
        server.agentKeys() == null ? List.of() : server.agentKeys(), false, lastUsed,
        "/connectors/mcp/" + server.id());
  }

  // ── Devices ───────────────────────────────────────────────────────────────

  private Card deviceCard(Access access, DeviceDto device, List<DeviceActionSpec> catalog) {
    Map<String, ActionType> catalogTypes = catalog.stream()
        .collect(Collectors.toMap(DeviceActionSpec::action, DeviceActionSpec::actionType,
            (a, b) -> a));
    List<ActionType> types = new ArrayList<>();
    if (device.actions() != null) {
      for (String declared : device.actions()) {
        String name = declared.contains("@") ? declared.substring(0, declared.indexOf('@'))
            : declared;
        if (device.blockedActions() != null && device.blockedActions().contains(name)) continue;
        if (catalogTypes.containsKey(name)) types.add(catalogTypes.get(name));
      }
    }
    if (device.tools() != null) device.tools().forEach(t -> types.add(t.actionType()));
    String status = device.status() == DeviceStatus.DISABLED ? "off"
        : device.online() ? "ready" : "offline";
    Instant lastUsed = device.lastSeenAt();
    try {
      lastUsed = deviceUseCase.commands(access, device.id(), 1).stream()
          .map(DeviceCommandDto::createdAt).filter(Objects::nonNull).findFirst().orElse(null);
    } catch (RuntimeException e) {
      log.debug("Could not read device {}'s last command", device.id(), e);
    }
    return new Card(DEVICE, device.id(), device.name(),
        device.platform() == null ? null : device.platform().name().toLowerCase(),
        status, null, types.size(), Types.of(types), devices.agentKeys(device.id()), false,
        lastUsed, "/settings/devices?device=" + device.id());
  }

  // ── Agents ────────────────────────────────────────────────────────────────

  private Map<UUID, String> agentKeysById(Access access) {
    Map<UUID, String> keys = new HashMap<>();
    for (AgentDto agent : agents.list(access.userId())) {
      if (agent.key() != null) keys.put(agent.id(), agent.key());
    }
    return keys;
  }

  private static UUID idOf(Map<UUID, String> keys, String key) {
    return keys.entrySet().stream().filter(e -> e.getValue().equals(key)).map(Map.Entry::getKey)
        .findFirst().orElse(null);
  }

  /** The person's own agent keys, each once, or just {@code "*"}. Unknown keys are an error. */
  private List<String> clean(Access access, List<String> requested) {
    if (requested == null) throw new ValidationException("Say which agents may use it");
    Set<String> own = agents.list(access.userId()).stream().map(AgentDto::key)
        .filter(Objects::nonNull).collect(Collectors.toSet());
    List<String> out = new ArrayList<>();
    for (String raw : requested) {
      if (raw == null || raw.isBlank()) continue;
      String key = raw.strip();
      if (key.equals(EVERY_AGENT)) return List.of(EVERY_AGENT);
      if (!own.contains(key)) throw new NotFoundException("Agent", key);
      if (!out.contains(key)) out.add(key);
    }
    out.sort(Comparator.comparing((String k) -> !k.equals(Devices.DEFAULT_AGENT))
        .thenComparing(Function.identity()));
    return out;
  }
}
