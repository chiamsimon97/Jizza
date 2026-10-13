package com.jizza.platform.usecase.connector;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.agent.CapabilityRegistry;
import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.connector.ConnectionSettings;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.enums.AuthScheme;
import com.jizza.engine.api.enums.CapabilityType;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.orchestrator.AgentDirectory;
import com.jizza.engine.api.service.ServiceDefinitions;
import com.jizza.engine.api.service.ServiceModel;
import com.jizza.engine.api.service.ServiceModel.Action;
import com.jizza.engine.api.service.ServiceModel.ActionInput;
import com.jizza.engine.api.service.ServiceModel.Api;
import com.jizza.engine.api.service.ServiceModel.ApiInput;
import com.jizza.engine.api.service.ServiceModel.Info;
import com.jizza.engine.api.service.ServiceModel.InfoPatch;
import com.jizza.engine.api.service.ServiceModel.Resource;
import com.jizza.engine.api.service.ServiceModel.ResourceInput;
import com.jizza.engine.api.service.ServiceModel.Run;
import com.jizza.engine.api.service.ServiceModel.Trigger;
import com.jizza.engine.api.service.ServiceModel.TriggerInput;
import com.jizza.engine.api.service.ServiceModel.TriggerSample;
import com.jizza.engine.api.service.ServiceModel.Variable;
import com.jizza.engine.api.service.ServiceModel.VariableInput;
import com.jizza.engine.api.service.ServiceRunner;
import com.jizza.engine.api.tool.ToolResult;
import com.jizza.engine.api.util.Json;
import com.jizza.platform.usecase.connection.ConnectionUseCase;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Connector (Service) module: building a third-party integration out of its authentication,
 * APIs, resources, actions, triggers and variables, then publishing it so agents can use it.
 *
 * <p>The product rules live here: every route checks the service belongs to the signed-in user,
 * names are validated before they are stored, a service is only published once it can actually
 * work, and agents only get the specific actions the user hands them.
 */
@Service
public class ConnectorServiceUseCase {

  /** Machine names end up in tool names and URLs, so they are kept plain. */
  private static final Pattern MACHINE_NAME = Pattern.compile("[a-z][a-z0-9_]{0,47}");
  private static final Pattern VARIABLE_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,47}");
  private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");
  private static final Set<String> API_KINDS = Set.of("request", "polling", "webhook");
  private static final int MAX_LABEL = 120;

  /** Everything the editor shows for one service. */
  public record ServiceDetail(Info service, List<Api> apis, List<Resource> resources,
                              List<Action> actions, List<Trigger> triggers,
                              List<Variable> variables, String oauthRedirectUri) {}

  public record NewService(String name, String description, String category, String baseUrl,
                           AuthScheme authScheme) {}

  private final ServiceDefinitions definitions;
  private final ServiceRunner runner;
  private final ConnectionUseCase connections;
  private final CapabilityRegistry capabilities;
  private final AgentDirectory agents;
  private final AuditLog audit;
  private final String oauthRedirectUri;

  public ConnectorServiceUseCase(ServiceDefinitions definitions, ServiceRunner runner,
                                 ConnectionUseCase connections, CapabilityRegistry capabilities,
                                 AgentDirectory agents, AuditLog audit,
                                 @org.springframework.beans.factory.annotation.Value(
                                     "${jizza.connectors.callback-base-url:http://localhost:8080}"
                                     + "${jizza.connectors.callback-path:/api/oauth/callback}")
                                 String oauthRedirectUri) {
    this.definitions = definitions;
    this.runner = runner;
    this.connections = connections;
    this.capabilities = capabilities;
    this.agents = agents;
    this.audit = audit;
    this.oauthRedirectUri = oauthRedirectUri;
  }

  // ── Services ──────────────────────────────────────────────────────────────

  public List<Info> list(UUID userId) {
    return definitions.list(userId);
  }

  public ServiceDetail detail(UUID userId, UUID serviceId) {
    Info info = owned(userId, serviceId);
    return new ServiceDetail(info, definitions.apis(serviceId), definitions.resources(serviceId),
        definitions.actions(serviceId), definitions.triggers(serviceId),
        definitions.variables(serviceId), oauthRedirectUri);
  }

  /** A new service starts as a draft with the chosen auth scheme; credentials come later. */
  @Transactional
  public Info create(UUID userId, NewService in) {
    String name = required(in.name(), "A service needs a name");
    AuthScheme scheme = in.authScheme() == null ? AuthScheme.NO_AUTH : in.authScheme();
    if (scheme == AuthScheme.OAUTH2) {
      // OAuth needs its URLs and client id before it is valid; those are set on the
      // Authentication tab, so the service starts without auth and is switched over there.
      scheme = AuthScheme.NO_AUTH;
    }
    var created = connections.create(userId, new ConnectionSettings(name, scheme, null, null, null,
        null, null, null, null, null, null, null, null, blankToNull(in.baseUrl()), null, null, null,
        null, null, null));
    Info info = definitions.update(created.id(), new InfoPatch(null,
        blankToNull(in.description()), blankToNull(in.category()), null));
    audit.record(userId, ActorType.USER, null, "service.created", name,
        Json.obj().put("serviceId", info.id().toString()));
    return info;
  }

  @Transactional
  public Info update(UUID userId, UUID serviceId, InfoPatch patch) {
    owned(userId, serviceId);
    if (patch.name() != null) required(patch.name(), "A service needs a name");
    if (patch.baseUrl() != null && !patch.baseUrl().isBlank()
        && !patch.baseUrl().startsWith("https://") && !patch.baseUrl().startsWith("{{")) {
      throw new ValidationException("The base URL must start with https://");
    }
    return definitions.update(serviceId, patch);
  }

  @Transactional
  public void delete(UUID userId, UUID serviceId) {
    owned(userId, serviceId);
    for (Action action : definitions.actions(serviceId)) {
      definitions.deleteAction(serviceId, action.id());
    }
    connections.delete(userId, serviceId);
  }

  /**
   * Publishing makes the service's actions reachable by the agents they are assigned to, and starts
   * its triggers polling. It is refused while anything that would make it fail is missing.
   */
  @Transactional
  public Info publish(UUID userId, UUID serviceId) {
    Info info = owned(userId, serviceId);
    List<String> problems = new ArrayList<>();
    if (info.baseUrl() == null || info.baseUrl().isBlank()) problems.add("set a base URL");
    if (info.authScheme() != AuthScheme.NO_AUTH && info.authScheme() != AuthScheme.CUSTOM
        && !info.credentialPresent()) {
      problems.add("connect the account or add its credential on the Authentication tab");
    }
    boolean hasWork = definitions.actions(serviceId).stream().anyMatch(Action::enabled)
        || definitions.triggers(serviceId).stream().anyMatch(Trigger::enabled);
    if (!hasWork) problems.add("add at least one enabled action or trigger");
    if (!problems.isEmpty()) {
      throw new ValidationException("Before publishing, " + String.join("; ", problems) + ".");
    }
    Info live = definitions.setVersionStatus(serviceId, ServiceModel.LIVE);
    audit.record(userId, ActorType.USER, null, "service.published", info.name(),
        Json.obj().put("serviceId", serviceId.toString()).put("version", live.liveVersion()));
    return live;
  }

  @Transactional
  public Info unpublish(UUID userId, UUID serviceId) {
    Info info = owned(userId, serviceId);
    audit.record(userId, ActorType.USER, null, "service.unpublished", info.name(), null);
    return definitions.setVersionStatus(serviceId, ServiceModel.DRAFT);
  }

  // ── APIs ──────────────────────────────────────────────────────────────────

  @Transactional
  public Api saveApi(UUID userId, UUID serviceId, UUID apiId, ApiInput in) {
    owned(userId, serviceId);
    machineName(in.name(), "API");
    label(in.label());
    String method = in.httpMethod() == null ? "" : in.httpMethod().toUpperCase(Locale.ROOT);
    if (!METHODS.contains(method)) {
      throw new ValidationException("The method must be one of " + String.join(", ", METHODS));
    }
    if (in.kind() != null && !API_KINDS.contains(in.kind())) {
      throw new ValidationException("Unknown API kind '" + in.kind() + "'");
    }
    String path = required(in.pathTemplate(), "An API needs a path, such as /v1/invoices");
    if (path.contains("://")) {
      throw new ValidationException("Give the path only; the host comes from the service's base URL");
    }
    if (in.inputSchema() != null && !"object".equals(in.inputSchema().path("type").asText())) {
      throw new ValidationException("The input schema must be a JSON object schema");
    }
    return definitions.saveApi(serviceId, apiId, new ApiInput(in.name(), in.label().trim(),
        in.description(), in.kind(), method, path, in.inputSchema(), in.paramMapping(),
        in.sideEffecting()));
  }

  @Transactional
  public void deleteApi(UUID userId, UUID serviceId, UUID apiId) {
    owned(userId, serviceId);
    definitions.deleteApi(serviceId, apiId);
  }

  // ── Resources ─────────────────────────────────────────────────────────────

  @Transactional
  public Resource saveResource(UUID userId, UUID serviceId, UUID resourceId, ResourceInput in) {
    owned(userId, serviceId);
    machineName(in.name(), "resource");
    label(in.label());
    JsonNode fields = in.fields() == null ? Json.mapper().createArrayNode() : in.fields();
    if (!fields.isArray()) throw new ValidationException("Resource fields must be a list");
    Set<String> keys = new HashSet<>();
    for (JsonNode field : fields) {
      String key = field.path("key").asText("");
      if (key.isBlank()) throw new ValidationException("Every resource field needs a key");
      if (!keys.add(key)) throw new ValidationException("Field '" + key + "' appears twice");
    }
    return definitions.saveResource(serviceId, resourceId, new ResourceInput(in.name(),
        in.label().trim(), in.description(), fields, blankToNull(in.idField())));
  }

  @Transactional
  public void deleteResource(UUID userId, UUID serviceId, UUID resourceId) {
    owned(userId, serviceId);
    definitions.deleteResource(serviceId, resourceId);
  }

  // ── Actions ───────────────────────────────────────────────────────────────

  @Transactional
  public Action saveAction(UUID userId, UUID serviceId, UUID actionId, ActionInput in) {
    owned(userId, serviceId);
    machineName(in.name(), "action");
    label(in.label());
    if (in.apiId() == null) throw new ValidationException("An action must be built on an API");
    return definitions.saveAction(serviceId, actionId, new ActionInput(in.name(),
        in.label().trim(), in.description(), in.apiId(), in.resourceId(), in.enabled()));
  }

  @Transactional
  public void deleteAction(UUID userId, UUID serviceId, UUID actionId) {
    owned(userId, serviceId);
    definitions.deleteAction(serviceId, actionId);
  }

  /** Sets exactly which of the user's agents may use an action. */
  @Transactional
  public Action assignAgents(UUID userId, UUID serviceId, UUID actionId, List<UUID> agentIds) {
    owned(userId, serviceId);
    Action action = actionOf(serviceId, actionId);
    Set<UUID> wanted = new HashSet<>(agentIds == null ? List.of() : agentIds);
    for (UUID agentId : wanted) {
      agents.byId(agentId).filter(a -> a.userId().equals(userId))
          .orElseThrow(() -> new NotFoundException("Agent", agentId));
    }
    for (UUID agentId : wanted) {
      if (!action.agentIds().contains(agentId)) {
        capabilities.grant(agentId, CapabilityType.SERVICE_ACTION, actionId);
      }
    }
    for (UUID agentId : action.agentIds()) {
      if (!wanted.contains(agentId)) {
        capabilities.revoke(agentId, CapabilityType.SERVICE_ACTION, actionId);
      }
    }
    return actionOf(serviceId, actionId);
  }

  /** Runs an action once from the editor, against the real service, and records it in history. */
  public ToolResult testAction(UUID userId, UUID serviceId, UUID actionId, JsonNode input) {
    owned(userId, serviceId);
    actionOf(serviceId, actionId);
    return runner.runAction(serviceId, actionId, input, "test", null);
  }

  // ── Triggers ──────────────────────────────────────────────────────────────

  @Transactional
  public Trigger saveTrigger(UUID userId, UUID serviceId, UUID triggerId, TriggerInput in) {
    owned(userId, serviceId);
    machineName(in.name(), "trigger");
    label(in.label());
    if (in.apiId() == null) throw new ValidationException("A trigger must poll an API");
    if (in.pollIntervalMinutes() != null
        && (in.pollIntervalMinutes() < 1 || in.pollIntervalMinutes() > 1440)) {
      throw new ValidationException("The polling interval must be between 1 and 1440 minutes");
    }
    return definitions.saveTrigger(serviceId, triggerId, new TriggerInput(in.name(),
        in.label().trim(), in.description(), in.apiId(), in.resourceId(),
        in.pollIntervalMinutes(), blankToNull(in.itemsPath()), blankToNull(in.idField()),
        in.enabled()));
  }

  @Transactional
  public void deleteTrigger(UUID userId, UUID serviceId, UUID triggerId) {
    owned(userId, serviceId);
    definitions.deleteTrigger(serviceId, triggerId);
  }

  public TriggerSample testTrigger(UUID userId, UUID serviceId, UUID triggerId) {
    owned(userId, serviceId);
    return runner.testTrigger(serviceId, triggerId);
  }

  // ── Variables ─────────────────────────────────────────────────────────────

  @Transactional
  public Variable saveVariable(UUID userId, UUID serviceId, UUID variableId, VariableInput in) {
    owned(userId, serviceId);
    if (in.name() == null || !VARIABLE_NAME.matcher(in.name()).matches()) {
      throw new ValidationException("Variable names start with a letter and use letters, digits "
          + "and underscores");
    }
    boolean secret = Boolean.TRUE.equals(in.secret());
    if (variableId == null && (in.value() == null || in.value().isEmpty())) {
      throw new ValidationException("A new variable needs a value");
    }
    return definitions.saveVariable(serviceId, variableId,
        new VariableInput(in.name(), in.description(), in.value(), secret));
  }

  @Transactional
  public void deleteVariable(UUID userId, UUID serviceId, UUID variableId) {
    owned(userId, serviceId);
    definitions.deleteVariable(serviceId, variableId);
  }

  // ── History ───────────────────────────────────────────────────────────────

  public List<Run> history(UUID userId, UUID serviceId, int limit) {
    owned(userId, serviceId);
    return definitions.runs(serviceId, limit);
  }

  // ── Rules ─────────────────────────────────────────────────────────────────

  private Info owned(UUID userId, UUID serviceId) {
    connections.ownedBy(userId, serviceId);
    return definitions.find(serviceId).orElseThrow(() -> new NotFoundException("Service", serviceId));
  }

  private Action actionOf(UUID serviceId, UUID actionId) {
    return definitions.action(actionId).filter(a -> a.serviceId().equals(serviceId))
        .orElseThrow(() -> new NotFoundException("Action", actionId));
  }

  static void machineName(String name, String what) {
    if (name == null || !MACHINE_NAME.matcher(name).matches()) {
      throw new ValidationException("The " + what + " name must start with a letter and use only "
          + "lower-case letters, digits and underscores (for example create_invoice)");
    }
  }

  private static void label(String label) {
    String value = required(label, "A display name is required");
    if (value.length() > MAX_LABEL) {
      throw new ValidationException("Display names are limited to " + MAX_LABEL + " characters");
    }
  }

  private static String required(String value, String message) {
    if (value == null || value.isBlank()) throw new ValidationException(message);
    return value.trim();
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
