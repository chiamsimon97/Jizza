package com.jizza.engine.connectors.tools.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.crypto.EncryptionService;
import com.jizza.engine.api.enums.CapabilityType;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.service.ServiceDefinitions;
import com.jizza.engine.api.error.ValidationException;
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
import com.jizza.engine.api.service.ServiceModel.Variable;
import com.jizza.engine.api.service.ServiceModel.VariableInput;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.persistence.entity.AgentCapabilityEntity;
import com.jizza.engine.persistence.entity.ConnectionEntity;
import com.jizza.engine.persistence.entity.GeneratedToolEntity;
import com.jizza.engine.persistence.entity.ServiceActionEntity;
import com.jizza.engine.persistence.entity.ServiceResourceEntity;
import com.jizza.engine.persistence.entity.ServiceRunEntity;
import com.jizza.engine.persistence.entity.ServiceTriggerEntity;
import com.jizza.engine.persistence.entity.ServiceVariableEntity;
import com.jizza.engine.persistence.repository.AgentCapabilityRepository;
import com.jizza.engine.persistence.repository.ConnectionRepository;
import com.jizza.engine.persistence.repository.GeneratedToolRepository;
import com.jizza.engine.persistence.repository.ServiceActionRepository;
import com.jizza.engine.persistence.repository.ServiceResourceRepository;
import com.jizza.engine.persistence.repository.ServiceRunRepository;
import com.jizza.engine.persistence.repository.ServiceTriggerRepository;
import com.jizza.engine.persistence.repository.ServiceVariableRepository;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service definitions over JPA. Every child row is looked up through its service id: an id that
 * belongs to another service is reported as not found rather than acted on.
 */
@Service
public class JpaServiceDefinitions implements ServiceDefinitions {

  private final ConnectionRepository services;
  private final GeneratedToolRepository apis;
  private final ServiceResourceRepository resources;
  private final ServiceActionRepository actions;
  private final ServiceTriggerRepository triggers;
  private final ServiceVariableRepository variables;
  private final ServiceRunRepository runs;
  private final AgentCapabilityRepository capabilities;
  private final EncryptionService encryption;

  public JpaServiceDefinitions(ConnectionRepository services, GeneratedToolRepository apis,
                               ServiceResourceRepository resources, ServiceActionRepository actions,
                               ServiceTriggerRepository triggers, ServiceVariableRepository variables,
                               ServiceRunRepository runs, AgentCapabilityRepository capabilities,
                               EncryptionService encryption) {
    this.services = services;
    this.apis = apis;
    this.resources = resources;
    this.actions = actions;
    this.triggers = triggers;
    this.variables = variables;
    this.runs = runs;
    this.capabilities = capabilities;
    this.encryption = encryption;
  }

  private com.jizza.engine.connectors.config.ConnectorProperties connectorProperties;
  private com.jizza.engine.persistence.repository.WorkflowRepository workflows;

  /** Where webhook triggers are reached, and which workflows each trigger starts. */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setTriggerSupport(com.jizza.engine.connectors.config.ConnectorProperties properties,
                                com.jizza.engine.persistence.repository.WorkflowRepository workflows) {
    this.connectorProperties = properties;
    this.workflows = workflows;
  }

  /** The public address of a webhook trigger, or null while it has none. */
  public String webhookUrl(String token) {
    if (token == null) return null;
    String base = connectorProperties == null || connectorProperties.getCallbackBaseUrl() == null
        ? "" : connectorProperties.getCallbackBaseUrl().replaceAll("/+$", "");
    return base + "/api/webhooks/services/" + token;
  }

  // ── Service ───────────────────────────────────────────────────────────────

  @Override
  @Transactional(readOnly = true)
  public List<Info> list(UUID workspaceId) {
    return services.findByWorkspaceIdOrderByServiceNameAsc(workspaceId).stream().map(this::info).toList();
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<Info> find(UUID serviceId) {
    return services.findById(serviceId).map(this::info);
  }

  @Override
  @Transactional
  public Info update(UUID serviceId, InfoPatch patch) {
    ConnectionEntity s = service(serviceId);
    if (patch.name() != null) s.setServiceName(patch.name());
    if (patch.description() != null) s.setDescription(patch.description());
    if (patch.category() != null) s.setCategory(patch.category());
    if (patch.baseUrl() != null) s.setBaseUrl(patch.baseUrl());
    return info(services.save(s));
  }

  @Override
  @Transactional
  public Info setVersionStatus(UUID serviceId, String versionStatus) {
    ConnectionEntity s = service(serviceId);
    s.setVersionStatus(versionStatus);
    if (ServiceModel.LIVE.equals(versionStatus)) {
      s.setLiveVersion(s.getLiveVersion() + 1);
      s.setPublishedAt(Instant.now());
    }
    return info(services.save(s));
  }

  // ── APIs ──────────────────────────────────────────────────────────────────

  @Override
  @Transactional(readOnly = true)
  public List<Api> apis(UUID serviceId) {
    return apis.findByConnectionId(serviceId).stream().map(JpaServiceDefinitions::api).toList();
  }

  @Override
  @Transactional
  public Api saveApi(UUID serviceId, UUID apiId, ApiInput in) {
    GeneratedToolEntity e = apiId == null ? new GeneratedToolEntity() : apiEntity(serviceId, apiId);
    if (apiId == null) {
      e.setConnectionId(serviceId);
      e.setEnabled(true);
    }
    e.setName(in.name());
    e.setLabel(in.label());
    e.setDescription(in.description());
    e.setKind(in.kind() == null ? "request" : in.kind());
    e.setHttpMethod(in.httpMethod().toUpperCase(Locale.ROOT));
    e.setPathTemplate(in.pathTemplate());
    e.setInputSchema(in.inputSchema() == null ? emptySchema() : in.inputSchema());
    e.setParamMapping(in.paramMapping() == null ? Json.obj() : in.paramMapping());
    e.setSideEffecting(in.sideEffecting() != null ? in.sideEffecting()
        : !"GET".equals(e.getHttpMethod()));
    return api(apis.save(e));
  }

  @Override
  @Transactional
  public void deleteApi(UUID serviceId, UUID apiId) {
    apis.delete(apiEntity(serviceId, apiId));
  }

  // ── Resources ─────────────────────────────────────────────────────────────

  @Override
  @Transactional(readOnly = true)
  public List<Resource> resources(UUID serviceId) {
    return resources.findByConnectionIdOrderByLabelAsc(serviceId).stream()
        .map(JpaServiceDefinitions::resource).toList();
  }

  @Override
  @Transactional
  public Resource saveResource(UUID serviceId, UUID resourceId, ResourceInput in) {
    ServiceResourceEntity e = resourceId == null ? new ServiceResourceEntity()
        : scoped(resources.findById(resourceId), serviceId, ServiceResourceEntity::getConnectionId,
            "Resource", resourceId);
    e.setConnectionId(serviceId);
    e.setName(in.name());
    e.setLabel(in.label());
    e.setDescription(in.description());
    e.setFields(in.fields() == null ? Json.mapper().createArrayNode() : in.fields());
    e.setIdField(in.idField());
    return resource(resources.save(e));
  }

  @Override
  @Transactional
  public void deleteResource(UUID serviceId, UUID resourceId) {
    resources.delete(scoped(resources.findById(resourceId), serviceId,
        ServiceResourceEntity::getConnectionId, "Resource", resourceId));
  }

  // ── Actions ───────────────────────────────────────────────────────────────

  @Override
  @Transactional(readOnly = true)
  public List<Action> actions(UUID serviceId) {
    ConnectionEntity s = service(serviceId);
    return actions.findByConnectionIdOrderByLabelAsc(serviceId).stream()
        .map(a -> action(s, a)).toList();
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<Action> action(UUID actionId) {
    return actions.findById(actionId)
        .map(a -> action(service(a.getConnectionId()), a));
  }

  @Override
  @Transactional
  public Action saveAction(UUID serviceId, UUID actionId, ActionInput in) {
    ServiceActionEntity e = actionId == null ? new ServiceActionEntity()
        : scoped(actions.findById(actionId), serviceId, ServiceActionEntity::getConnectionId,
            "Action", actionId);
    apiEntity(serviceId, in.apiId());
    if (in.resourceId() != null) {
      scoped(resources.findById(in.resourceId()), serviceId, ServiceResourceEntity::getConnectionId,
          "Resource", in.resourceId());
    }
    e.setConnectionId(serviceId);
    e.setName(in.name());
    e.setLabel(in.label());
    e.setDescription(in.description());
    e.setApiId(in.apiId());
    e.setResourceId(in.resourceId());
    if (in.enabled() != null) e.setEnabled(in.enabled());
    return action(service(serviceId), actions.save(e));
  }

  @Override
  @Transactional
  public void deleteAction(UUID serviceId, UUID actionId) {
    ServiceActionEntity e = scoped(actions.findById(actionId), serviceId,
        ServiceActionEntity::getConnectionId, "Action", actionId);
    // An agent must not keep a grant to an action that no longer exists.
    capabilities.deleteAll(capabilities.findByCapabilityTypeAndCapabilityRef(
        CapabilityType.SERVICE_ACTION, actionId));
    actions.delete(e);
  }

  // ── Triggers ──────────────────────────────────────────────────────────────

  @Override
  @Transactional(readOnly = true)
  public List<Trigger> triggers(UUID serviceId) {
    return triggers.findByConnectionIdOrderByLabelAsc(serviceId).stream()
        .map(this::trigger).toList();
  }

  @Override
  @Transactional
  public Trigger saveTrigger(UUID serviceId, UUID triggerId, TriggerInput in) {
    ServiceTriggerEntity e = triggerId == null ? new ServiceTriggerEntity()
        : scoped(triggers.findById(triggerId), serviceId, ServiceTriggerEntity::getConnectionId,
            "Trigger", triggerId);
    String kind = in.kind() == null ? (e.getKind() == null ? ServiceModel.POLLING : e.getKind())
        : in.kind();
    if (!ServiceModel.POLLING.equals(kind) && !ServiceModel.WEBHOOK.equals(kind)) {
      throw new ValidationException("A trigger polls or receives a webhook");
    }
    if (in.apiId() != null) apiEntity(serviceId, in.apiId());
    if (in.resourceId() != null) {
      scoped(resources.findById(in.resourceId()), serviceId, ServiceResourceEntity::getConnectionId,
          "Resource", in.resourceId());
    }
    if (triggerId == null) e.setSeenIds(Json.mapper().createArrayNode());
    e.setConnectionId(serviceId);
    e.setName(in.name());
    e.setLabel(in.label());
    e.setDescription(in.description());
    // Pointing a trigger at a different API or list makes what it has already seen meaningless.
    if (triggerId != null && (!java.util.Objects.equals(in.apiId(), e.getApiId())
        || !java.util.Objects.equals(in.itemsPath(), e.getItemsPath()))) {
      e.setSeenIds(Json.mapper().createArrayNode());
      e.setLastPolledAt(null);
    }
    e.setApiId(in.apiId());
    e.setResourceId(in.resourceId());
    if (in.pollIntervalMinutes() != null) e.setPollIntervalMinutes(in.pollIntervalMinutes());
    e.setItemsPath(in.itemsPath());
    e.setIdField(in.idField() == null || in.idField().isBlank() ? "id" : in.idField());
    if (in.enabled() != null) e.setEnabled(in.enabled());
    e.setKind(kind);
    if (ServiceModel.WEBHOOK.equals(kind) && e.getEndpointToken() == null) {
      byte[] bytes = new byte[32];
      new java.security.SecureRandom().nextBytes(bytes);
      e.setEndpointToken(java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
    }
    if (in.signingSecret() != null) {
      e.setSigningSecret(in.signingSecret().isBlank() ? null
          : encryption.encryptString(in.signingSecret()));
    }
    if (in.signatureHeader() != null) {
      e.setSignatureHeader(in.signatureHeader().isBlank() ? null : in.signatureHeader().strip());
    }
    if (in.signaturePrefix() != null) {
      e.setSignaturePrefix(in.signaturePrefix().isEmpty() ? null : in.signaturePrefix());
    }
    return trigger(triggers.save(e));
  }

  @Override
  @Transactional
  public void deleteTrigger(UUID serviceId, UUID triggerId) {
    triggers.delete(scoped(triggers.findById(triggerId), serviceId,
        ServiceTriggerEntity::getConnectionId, "Trigger", triggerId));
  }

  // ── Variables ─────────────────────────────────────────────────────────────

  @Override
  @Transactional(readOnly = true)
  public List<Variable> variables(UUID serviceId) {
    return variables.findByConnectionIdOrderByNameAsc(serviceId).stream()
        .map(JpaServiceDefinitions::variable).toList();
  }

  @Override
  @Transactional
  public Variable saveVariable(UUID serviceId, UUID variableId, VariableInput in) {
    ServiceVariableEntity e = variableId == null ? new ServiceVariableEntity()
        : scoped(variables.findById(variableId), serviceId, ServiceVariableEntity::getConnectionId,
            "Variable", variableId);
    e.setConnectionId(serviceId);
    e.setName(in.name());
    e.setDescription(in.description());
    boolean secret = in.secret() != null ? in.secret() : e.isSecret();
    // A blank value on an existing secret means "keep it": the client never sees the old one.
    boolean keepSecret = secret && e.isSecret() && (in.value() == null || in.value().isEmpty());
    if (!keepSecret) {
      e.setValue(secret ? null : in.value());
      e.setSecretValue(secret ? encryption.encryptString(in.value()) : null);
    }
    e.setSecret(secret);
    return variable(variables.save(e));
  }

  @Override
  @Transactional
  public void deleteVariable(UUID serviceId, UUID variableId) {
    variables.delete(scoped(variables.findById(variableId), serviceId,
        ServiceVariableEntity::getConnectionId, "Variable", variableId));
  }

  // ── History ───────────────────────────────────────────────────────────────

  @Override
  @Transactional(readOnly = true)
  public List<Run> runs(UUID serviceId, int limit) {
    return runs.findByConnectionIdOrderByCreatedAtDesc(serviceId,
            PageRequest.of(0, Math.max(1, Math.min(limit, 200))))
        .stream().map(JpaServiceDefinitions::run).toList();
  }

  // ── Mapping and scoping ──────────────────────────────────────────────────

  private ConnectionEntity service(UUID serviceId) {
    return services.findById(serviceId).orElseThrow(() -> new NotFoundException("Service", serviceId));
  }

  GeneratedToolEntity apiEntity(UUID serviceId, UUID apiId) {
    return scoped(apis.findById(apiId), serviceId, GeneratedToolEntity::getConnectionId, "API",
        apiId);
  }

  static <T> T scoped(Optional<T> row, UUID serviceId, Function<T, UUID> owner, String what,
                      UUID id) {
    return row.filter(r -> serviceId.equals(owner.apply(r)))
        .orElseThrow(() -> new NotFoundException(what, id));
  }

  private Info info(ConnectionEntity s) {
    UUID id = s.getId();
    return new Info(id, s.getServiceName(), s.getSlug(), s.getDescription(), s.getCategory(),
        s.getBaseUrl(), s.getAuthScheme(), s.getStatus(),
        s.getAccessToken() != null || s.getClientSecret() != null,
        s.getVersionStatus(), s.getLiveVersion(), s.getPublishedAt(),
        apis.findByConnectionId(id).size(), actions.findByConnectionIdOrderByLabelAsc(id).size(),
        triggers.findByConnectionIdOrderByLabelAsc(id).size(), s.getCreatedAt(), s.getUpdatedAt());
  }

  static Api api(GeneratedToolEntity e) {
    return new Api(e.getId(), e.getConnectionId(), e.getName(),
        e.getLabel() == null ? e.getName() : e.getLabel(), e.getDescription(), e.getKind(),
        e.getHttpMethod(), e.getPathTemplate(), e.getInputSchema(), e.getParamMapping(),
        e.isSideEffecting(), e.isEnabled());
  }

  static Resource resource(ServiceResourceEntity e) {
    return new Resource(e.getId(), e.getConnectionId(), e.getName(), e.getLabel(),
        e.getDescription(), e.getFields(), e.getIdField());
  }

  private Action action(ConnectionEntity service, ServiceActionEntity e) {
    boolean sideEffecting = apis.findById(e.getApiId())
        .map(GeneratedToolEntity::isSideEffecting).orElse(true);
    List<UUID> agentIds = capabilities.findByCapabilityTypeAndCapabilityRef(
            CapabilityType.SERVICE_ACTION, e.getId()).stream()
        .map(AgentCapabilityEntity::getAgentId).toList();
    return new Action(e.getId(), e.getConnectionId(), e.getName(), e.getLabel(), e.getDescription(),
        e.getApiId(), e.getResourceId(), e.isEnabled(), sideEffecting,
        toolName(service.getSlug(), e.getName()), agentIds);
  }

  /** What the model calls the action: the service slug and action name, within the 64-char limit. */
  public static String toolName(String slug, String actionName) {
    String name = slug + "__" + actionName;
    return name.length() <= 64 ? name : name.substring(0, 64);
  }

  Trigger trigger(ServiceTriggerEntity e) {
    List<UUID> listening = workflows == null || e.getId() == null ? List.of()
        : workflows.findByServiceTriggerIdAndDeletedAtIsNull(e.getId()).stream()
            .map(com.jizza.engine.persistence.entity.WorkflowEntity::getId).toList();
    boolean webhook = ServiceModel.WEBHOOK.equals(e.getKind());
    return new Trigger(e.getId(), e.getConnectionId(), e.getName(), e.getLabel(),
        e.getDescription(), e.getApiId(), e.getResourceId(), e.getKind(),
        e.getPollIntervalMinutes(), e.getItemsPath(), e.getIdField(), e.isEnabled(),
        e.getLastPolledAt(), webhook ? webhookUrl(e.getEndpointToken()) : null,
        e.getSigningSecret() != null, e.getSignatureHeader(), e.getSignaturePrefix(),
        e.getLastReceivedAt(), listening);
  }

  static Variable variable(ServiceVariableEntity e) {
    boolean set = e.isSecret() ? e.getSecretValue() != null : e.getValue() != null;
    return new Variable(e.getId(), e.getConnectionId(), e.getName(), e.getDescription(),
        e.isSecret() ? null : e.getValue(), e.isSecret(), set);
  }

  static Run run(ServiceRunEntity e) {
    return new Run(e.getId(), e.getConnectionId(), e.getKind(), e.getRefId(), e.getRefName(),
        e.getSource(), e.getStatus(), e.getDurationMs(), e.getInput(), e.getOutput(), e.getError(),
        e.getCreatedAt());
  }

  private static JsonNode emptySchema() {
    return Json.obj().put("type", "object").set("properties", Json.obj());
  }
}
