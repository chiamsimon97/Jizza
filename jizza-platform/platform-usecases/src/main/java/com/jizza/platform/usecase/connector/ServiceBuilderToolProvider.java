package com.jizza.platform.usecase.connector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.authz.Access;
import com.jizza.engine.api.authz.Permissions;
import com.jizza.engine.api.autonomy.ActionTypeSource;
import com.jizza.engine.api.chat.ChatScope;
import com.jizza.engine.api.connector.ToolGenerator;
import com.jizza.engine.api.dto.AgentDto;
import com.jizza.engine.api.dto.GeneratedToolDto;
import com.jizza.engine.api.enums.ActionType;
import com.jizza.engine.api.enums.AuthScheme;
import com.jizza.engine.api.enums.DocFormat;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.orchestrator.AgentDirectory;
import com.jizza.engine.api.orchestrator.UserSteps;
import com.jizza.engine.api.service.ServiceModel;
import com.jizza.engine.api.service.ServiceModel.Action;
import com.jizza.engine.api.service.ServiceModel.ActionInput;
import com.jizza.engine.api.service.ServiceModel.Api;
import com.jizza.engine.api.service.ServiceModel.ApiInput;
import com.jizza.engine.api.service.ServiceModel.Info;
import com.jizza.engine.api.service.ServiceModel.Trigger;
import com.jizza.engine.api.service.ServiceModel.TriggerInput;
import com.jizza.engine.api.tool.JsonSchemas;
import com.jizza.engine.api.tool.ToolCall;
import com.jizza.engine.api.tool.ToolProvider;
import com.jizza.engine.api.tool.ToolResult;
import com.jizza.engine.api.tool.ToolSpec;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.workspace.WorkspaceDirectory;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Building a connector by chatting (K5): "connect my school portal" and Jizza, having read the
 * service's API docs (with its web tools or a document the person shares), drafts the service, adds
 * its APIs, actions and triggers, tries each action with the person and publishes it.
 *
 * <p>Jizza only. Everything here changes Jizza's own records, so it is a draft the person can see
 * and change in the service editor at every step. Two things are never done on Jizza's say-so:
 * trying an action that changes something in the service, and publishing; both always ask the
 * person. Credentials never pass through the chat: the person is sent to the service's
 * Authentication tab with a button.
 */
@Component
public class ServiceBuilderToolProvider implements ToolProvider, ActionTypeSource {

  public static final String LIST = "service_build_list";
  public static final String DRAFT = "service_build_draft";
  public static final String ADD_API = "service_build_add_api";
  public static final String IMPORT_DOC = "service_build_import_doc";
  public static final String ADD_ACTION = "service_build_add_action";
  public static final String ADD_TRIGGER = "service_build_add_trigger";
  public static final String TRY_READ = "service_build_try_read";
  public static final String TRY_CHANGE = "service_build_try_change";
  public static final String ASK_CREDENTIALS = "service_build_ask_credentials";
  public static final String PUBLISH = "service_build_publish";

  /** Tools that ask the person every time, whatever their rules say. */
  static final Set<String> ALWAYS_ASK = Set.of(TRY_CHANGE, PUBLISH);

  /** An API document pasted into a call; bigger ones are uploaded on the APIs tab. */
  static final int MAX_DOC_CHARS = 400_000;

  private final ObjectProvider<ConnectorServiceUseCase> services;
  private final AgentDirectory agents;
  private final ObjectProvider<ToolGenerator> generator;
  private final ObjectProvider<WorkspaceDirectory> workspaces;
  private final List<ToolSpec> specs;
  private final Set<String> names;

  public ServiceBuilderToolProvider(ObjectProvider<ConnectorServiceUseCase> services,
                                    AgentDirectory agents, ObjectProvider<ToolGenerator> generator,
                                    ObjectProvider<WorkspaceDirectory> workspaces) {
    this.services = services;
    this.agents = agents;
    this.generator = generator;
    this.workspaces = workspaces;
    this.specs = List.of(
        spec(LIST, "List the person's own services (connectors built in Jizza), drafts included, "
                + "with their APIs, actions and triggers. Use it before building, to carry on "
                + "with a draft rather than start a second one.",
            Map.of("serviceId", JsonSchemas.string("One service to show in full (optional)")),
            List.of()),
        spec(DRAFT, "Start a new service (a connector to an outside system with an HTTP API, such "
                + "as a school portal or a CRM) as a draft. Read the service's API docs first "
                + "(web_fetch on a docs link, or a document the person shared) so you know its "
                + "base URL and how it signs in. Nothing is reachable by any agent until it is "
                + "published.",
            props(
                "name", JsonSchemas.string("The service's name, as the person calls it"),
                "description", JsonSchemas.string("What it is for, one sentence"),
                "category", JsonSchemas.string("A short category, such as Education or CRM"),
                "baseUrl", JsonSchemas.string("The API's base URL, such as https://api.example.com/v1"),
                "auth", enumOf("How the API signs in: no_auth, api_key, basic, oauth2 or custom. "
                    + "The person adds the actual key or sign-in on the Authentication tab.",
                    List.of("no_auth", "api_key", "basic", "oauth2", "custom")),
                "docsUrl", JsonSchemas.string("The API docs you read, kept in the description")),
            List.of("name", "baseUrl")),
        spec(ADD_API, "Add one HTTP request the service answers, from its docs. An API is the raw "
                + "request; an action (service_build_add_action) is what agents may use.",
            props(
                "serviceId", JsonSchemas.string("The service"),
                "name", JsonSchemas.string("Machine name, lower case with underscores, such as list_homework"),
                "label", JsonSchemas.string("Display name, such as List homework"),
                "description", JsonSchemas.string("What it does, from the docs"),
                "method", enumOf("HTTP method", List.of("GET", "POST", "PUT", "PATCH", "DELETE")),
                "path", JsonSchemas.string("The path after the base URL, with {placeholders} for "
                    + "path inputs, such as /students/{student_id}/homework"),
                "inputs", inputsSchema(),
                "body", JsonSchemas.bool("True when the request takes a JSON body; the agent then "
                    + "passes it as the input 'body'"),
                "polling", JsonSchemas.bool("True for a GET that lists recent items, for a polling trigger")),
            List.of("serviceId", "name", "label", "method", "path")),
        spec(IMPORT_DOC, "Add every request from an OpenAPI (Swagger) document at once, in JSON or "
                + "YAML, as the docs publish it. Prefer it to adding APIs one by one when the "
                + "service has such a document.",
            props(
                "serviceId", JsonSchemas.string("The service"),
                "document", JsonSchemas.string("The whole OpenAPI document text")),
            List.of("serviceId", "document")),
        spec(ADD_ACTION, "Make an API usable by agents as an action, with a clear name. A new "
                + "action is given to Jizza; the person gives it to other agents.",
            props(
                "serviceId", JsonSchemas.string("The service"),
                "api", JsonSchemas.string("The API's id or machine name"),
                "name", JsonSchemas.string("Machine name, such as list_homework"),
                "label", JsonSchemas.string("Display name"),
                "description", JsonSchemas.string("When an agent should use it")),
            List.of("serviceId", "api", "name", "label")),
        spec(ADD_TRIGGER, "Add an event that starts the person's workflows: polling (Jizza checks a "
                + "GET API that lists items) or webhook (instant: the service calls Jizza at an "
                + "address you then give the person to paste into the service's settings).",
            props(
                "serviceId", JsonSchemas.string("The service"),
                "name", JsonSchemas.string("Machine name, such as homework_set"),
                "label", JsonSchemas.string("Display name, such as Homework set"),
                "kind", enumOf("polling or webhook", List.of("polling", "webhook")),
                "api", JsonSchemas.string("For polling: the GET API's id or machine name"),
                "itemsPath", JsonSchemas.string("Where the list of items is in the response or "
                    + "event, dot separated; blank for the list itself"),
                "idField", JsonSchemas.string("The field that identifies an item, default id"),
                "pollIntervalMinutes", JsonSchemas.integer("For polling: minutes between checks")),
            List.of("serviceId", "name", "label", "kind")),
        spec(TRY_READ, "Try an action that only reads (a GET) against the real service, with the "
                + "inputs given, and see what comes back. Recorded in the service's history.",
            tryProps(), List.of("serviceId", "action")),
        spec(TRY_CHANGE, "Try an action that changes something in the service (anything but a GET). "
                + "The person approves each try first; say what it will do before you call it.",
            tryProps(), List.of("serviceId", "action")),
        spec(ASK_CREDENTIALS, "Show the person a button to the service's Authentication tab, where "
                + "they add its key or sign in. Never ask for a key, password or token in chat.",
            props(
                "serviceId", JsonSchemas.string("The service"),
                "what", JsonSchemas.string("What they need from the service, such as an API key "
                    + "from Settings, then Developer")),
            List.of("serviceId")),
        spec(PUBLISH, "Publish the service so its actions are usable and its triggers run. The "
                + "person approves it. Do it after the actions were tried.",
            props("serviceId", JsonSchemas.string("The service")),
            List.of("serviceId")));
    this.names = Set.copyOf(specs.stream().map(ToolSpec::name).toList());
  }

  // ── ToolProvider ──────────────────────────────────────────────────────────

  @Override
  public List<ToolSpec> toolsFor(UUID agentId) {
    return jizza(agentId).isPresent() ? specs : List.of();
  }

  @Override
  public boolean supports(UUID agentId, String toolName) {
    return names.contains(toolName) && jizza(agentId).isPresent();
  }

  /** Building is setting up a connector in the workspace the person last worked in. */
  @Override
  public Optional<UUID> workspaceOf(UUID agentId, String toolName) {
    return names.contains(toolName) ? jizza(agentId).map(a -> workspace(a.userId()))
        : Optional.empty();
  }

  private UUID workspace(UUID userId) {
    WorkspaceDirectory directory = workspaces.getIfAvailable();
    if (directory == null) return userId;
    return directory.lastActiveWorkspace(userId)
        .orElseGet(() -> directory.personalWorkspaceOf(userId));
  }

  @Override
  public String requiredPermission(String toolName) {
    return switch (toolName) {
      case LIST -> Permissions.CONNECTOR_VIEW;
      case DRAFT -> Permissions.CONNECTOR_CREATE;
      default -> Permissions.CONNECTOR_MODIFY;
    };
  }

  @Override
  public Optional<ActionType> actionTypeOf(UUID agentId, String toolRef) {
    if (!names.contains(toolRef)) return Optional.empty();
    return Optional.of(switch (toolRef) {
      case LIST, TRY_READ, ASK_CREDENTIALS -> ActionType.READ;
      case TRY_CHANGE -> ActionType.UPDATE;
      default -> ActionType.DRAFT;
    });
  }

  @Override
  public boolean alwaysAsks(UUID agentId, String toolRef) {
    return ALWAYS_ASK.contains(toolRef);
  }

  @Override
  public ToolResult invoke(UUID agentId, ToolCall call) {
    Optional<AgentDto> jizza = jizza(agentId);
    if (jizza.isEmpty()) return ToolResult.error("Only Jizza builds services");
    Access access = new Access(jizza.get().userId(), workspace(jizza.get().userId()));
    JsonNode args = call.args() == null ? Json.obj() : call.args();
    try {
      return switch (call.name()) {
        case LIST -> list(access, args);
        case DRAFT -> draft(access, args);
        case ADD_API -> addApi(access, args);
        case IMPORT_DOC -> importDoc(access, args);
        case ADD_ACTION -> addAction(access, args);
        case ADD_TRIGGER -> addTrigger(access, args);
        case TRY_READ -> tryAction(access, args, false);
        case TRY_CHANGE -> tryAction(access, args, true);
        case ASK_CREDENTIALS -> askCredentials(access, args);
        case PUBLISH -> publish(access, args);
        default -> ToolResult.error("Unknown tool " + call.name());
      };
    } catch (ValidationException | NotFoundException e) {
      return ToolResult.error(e.getMessage());
    } catch (IllegalArgumentException e) {
      return ToolResult.error("That id isn't valid: " + e.getMessage());
    }
  }

  // ── Tools ─────────────────────────────────────────────────────────────────

  private ToolResult list(Access access, JsonNode args) {
    ConnectorServiceUseCase use = use();
    String one = args.path("serviceId").asText("");
    if (!one.isBlank()) return ToolResult.ok(describe(use.detail(access, UUID.fromString(one))));
    ObjectNode out = Json.obj();
    ArrayNode list = out.putArray("services");
    for (Info s : use.list(access)) {
      list.addObject().put("serviceId", s.id().toString()).put("name", s.name())
          .put("status", s.versionStatus()).put("baseUrl", s.baseUrl())
          .put("auth", s.authScheme() == null ? null : s.authScheme().dbValue())
          .put("signedIn", s.credentialPresent()).put("apis", s.apiCount())
          .put("actions", s.actionCount()).put("triggers", s.triggerCount());
    }
    return ToolResult.ok(out);
  }

  private ToolResult draft(Access access, JsonNode args) {
    AuthScheme auth = authOf(args.path("auth").asText("no_auth"));
    String description = text(args, "description");
    String docs = text(args, "docsUrl");
    if (docs != null) description = (description == null ? "" : description + " ") + "API docs: " + docs;
    Info info = use().create(access, new ConnectorServiceUseCase.NewService(
        text(args, "name"), description, text(args, "category"), text(args, "baseUrl"), auth));
    ObjectNode out = Json.obj().put("serviceId", info.id().toString()).put("name", info.name())
        .put("status", info.versionStatus()).put("editor", editorPath(info.id(), null));
    out.put("note", "Drafted. Add its APIs from the docs next (service_build_import_doc for an "
        + "OpenAPI document, otherwise service_build_add_api), then an action for each thing the "
        + "person wants agents to do."
        + (auth == AuthScheme.NO_AUTH ? ""
            : " It signs in with " + auth.dbValue().replace('_', ' ') + ": call "
                + "service_build_ask_credentials so the person adds the key on the Authentication "
                + "tab." + (args.path("auth").asText("").equals("oauth2")
                    ? " Sign-in with OAuth is set up on that tab too." : "")));
    return ToolResult.ok(out);
  }

  private ToolResult addApi(Access access, JsonNode args) {
    UUID serviceId = id(args, "serviceId");
    String method = args.path("method").asText("GET").toUpperCase(Locale.ROOT);
    ObjectNode properties = Json.obj();
    ArrayNode required = Json.mapper().createArrayNode();
    ObjectNode mapping = Json.obj();
    ArrayNode path = mapping.putArray("path");
    ArrayNode query = mapping.putArray("query");
    ArrayNode header = mapping.putArray("header");
    for (JsonNode input : args.path("inputs")) {
      String name = input.path("name").asText("").strip();
      if (name.isEmpty()) continue;
      String type = input.path("type").asText("string");
      ObjectNode prop = properties.putObject(name).put("type",
          Set.of("string", "integer", "number", "boolean").contains(type) ? type : "string");
      if (input.hasNonNull("description")) prop.put("description", input.path("description").asText());
      switch (input.path("in").asText("query")) {
        case "path" -> { path.add(name); required.add(name); }
        case "header" -> header.add(name);
        default -> query.add(name);
      }
      if (input.path("required").asBoolean(false) && !"path".equals(input.path("in").asText())) {
        required.add(name);
      }
    }
    String template = text(args, "path");
    if (template != null) {
      var placeholder = java.util.regex.Pattern.compile("\\{([^}]+)}").matcher(template);
      while (placeholder.find()) {
        String name = placeholder.group(1);
        boolean known = false;
        for (JsonNode p : path) known |= p.asText().equals(name);
        if (!known) {
          path.add(name);
          required.add(name);
          properties.putObject(name).put("type", "string");
        }
      }
    }
    if (args.path("body").asBoolean(false)) {
      properties.putObject("body").put("type", "object")
          .put("description", "The JSON body to send");
      mapping.put("bodyProperty", "body");
    }
    ObjectNode schema = Json.obj().put("type", "object");
    schema.set("properties", properties);
    if (!required.isEmpty()) schema.set("required", required);
    String kind = args.path("polling").asBoolean(false) ? "polling" : "request";
    Api api = use().saveApi(access, serviceId, null, new ApiInput(text(args, "name"),
        text(args, "label"), text(args, "description"), kind, method, template, schema, mapping,
        null));
    return ToolResult.ok(Json.obj().put("apiId", api.id().toString()).put("name", api.name())
        .put("note", "Added. Make it an action with service_build_add_action when agents should use it."));
  }

  private ToolResult importDoc(Access access, JsonNode args) {
    UUID serviceId = id(args, "serviceId");
    use().detail(access, serviceId);
    String doc = args.path("document").asText("");
    if (doc.isBlank()) return ToolResult.error("'document' is empty");
    if (doc.length() > MAX_DOC_CHARS) {
      return ToolResult.error("That document is too long to pass here. Ask the person to upload "
          + "it on the service's APIs tab.");
    }
    ToolGenerator tools = generator.getIfAvailable();
    if (tools == null) return ToolResult.error("Importing API documents isn't available here");
    DocFormat format = doc.stripLeading().startsWith("{") ? DocFormat.OPENAPI_JSON
        : DocFormat.OPENAPI_YAML;
    List<GeneratedToolDto> added = tools.fromApiDoc(serviceId,
        doc.getBytes(StandardCharsets.UTF_8), format);
    ObjectNode out = Json.obj().put("added", added.size());
    ArrayNode list = out.putArray("apis");
    for (GeneratedToolDto t : added.stream().limit(60).toList()) {
      list.addObject().put("apiId", t.id().toString()).put("name", t.name())
          .put("method", t.httpMethod()).put("path", t.pathTemplate());
    }
    out.put("note", added.isEmpty() ? "No requests were found in that document."
        : "Make actions of the ones the person needs; leave the rest as APIs.");
    return ToolResult.ok(out);
  }

  private ToolResult addAction(Access access, JsonNode args) {
    UUID serviceId = id(args, "serviceId");
    var detail = use().detail(access, serviceId);
    Api api = find(detail.apis(), args.path("api").asText(""), Api::id, Api::name, "API");
    Action action = use().saveAction(access, serviceId, null, new ActionInput(text(args, "name"),
        text(args, "label"), text(args, "description"), api.id(), null, true));
    return ToolResult.ok(Json.obj().put("actionId", action.id().toString())
        .put("name", action.name()).put("changesThings", action.sideEffecting())
        .put("note", "Added and given to Jizza. Try it with "
            + (action.sideEffecting() ? TRY_CHANGE + " (the person approves the try)" : TRY_READ)
            + "."));
  }

  private ToolResult addTrigger(Access access, JsonNode args) {
    UUID serviceId = id(args, "serviceId");
    String kind = args.path("kind").asText(ServiceModel.POLLING);
    UUID apiId = null;
    if (ServiceModel.POLLING.equals(kind)) {
      var detail = use().detail(access, serviceId);
      apiId = find(detail.apis(), args.path("api").asText(""), Api::id, Api::name, "API").id();
    }
    Integer interval = args.hasNonNull("pollIntervalMinutes")
        ? args.path("pollIntervalMinutes").asInt() : null;
    Trigger trigger = use().saveTrigger(access, serviceId, null, new TriggerInput(
        text(args, "name"), text(args, "label"), null, apiId, null, interval,
        text(args, "itemsPath"), text(args, "idField"), true, kind, null, null, null));
    ObjectNode out = Json.obj().put("triggerId", trigger.id().toString()).put("kind", trigger.kind());
    if (trigger.webhookUrl() != null) {
      out.put("webhookUrl", trigger.webhookUrl());
      out.put("note", "Give the person this address to paste into the service's webhook "
          + "settings. If the service signs its events, they add the signing secret on the "
          + "Triggers tab. A workflow starts from it once they pick it under \"Starts when\".");
    } else {
      out.put("note", "Jizza checks it once the service is published; the first check only takes "
          + "a baseline. A workflow starts from it once the person picks it under \"Starts when\".");
    }
    return ToolResult.ok(out);
  }

  private ToolResult tryAction(Access access, JsonNode args, boolean change) {
    UUID serviceId = id(args, "serviceId");
    var detail = use().detail(access, serviceId);
    Action action = find(detail.actions(), args.path("action").asText(""), Action::id,
        Action::name, "action");
    if (!change && action.sideEffecting()) {
      return ToolResult.error("'" + action.name() + "' changes something in the service. Tell the "
          + "person what the try will do, then use " + TRY_CHANGE + ", which they approve.");
    }
    JsonNode input = args.path("input").isObject() ? args.path("input") : Json.obj();
    ToolResult result = use().testAction(access, serviceId, action.id(), input);
    if (result.ok()) return result;
    Info service = detail.service();
    String hint = !service.credentialPresent() && service.authScheme() != AuthScheme.NO_AUTH
        ? " The service has no credential yet: use " + ASK_CREDENTIALS + "."
        : "";
    String error = result.error() == null ? "The try failed" : result.error().strip();
    if (!hint.isEmpty() && !error.endsWith(".")) error += ".";
    return ToolResult.error(error + hint);
  }

  private ToolResult askCredentials(Access access, JsonNode args) {
    UUID serviceId = id(args, "serviceId");
    Info service = use().detail(access, serviceId).service();
    String what = text(args, "what");
    ChatScope.record(UserSteps.credentialsCard(service.name(),
        editorPath(serviceId, "authentication"), what));
    return ToolResult.ok(Json.obj().put("done", true).put("note", "A button to the "
        + "Authentication tab is on the person's screen. End your turn and wait; they tell you "
        + "when it is done. Never ask them to paste the key here."));
  }

  private ToolResult publish(Access access, JsonNode args) {
    UUID serviceId = id(args, "serviceId");
    Info info = use().publish(access, serviceId);
    return ToolResult.ok(Json.obj().put("serviceId", info.id().toString())
        .put("status", info.versionStatus()).put("version", info.liveVersion())
        .put("note", "Published. Its actions work for the agents that hold them; give them to "
            + "other agents on the Connectors page."));
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

  private ObjectNode describe(ConnectorServiceUseCase.ServiceDetail detail) {
    Info s = detail.service();
    ObjectNode out = Json.obj().put("serviceId", s.id().toString()).put("name", s.name())
        .put("status", s.versionStatus()).put("baseUrl", s.baseUrl())
        .put("auth", s.authScheme() == null ? null : s.authScheme().dbValue())
        .put("signedIn", s.credentialPresent()).put("editor", editorPath(s.id(), null));
    ArrayNode apis = out.putArray("apis");
    for (Api a : detail.apis()) {
      apis.addObject().put("apiId", a.id().toString()).put("name", a.name())
          .put("method", a.httpMethod()).put("path", a.pathTemplate());
    }
    ArrayNode actions = out.putArray("actions");
    for (Action a : detail.actions()) {
      actions.addObject().put("actionId", a.id().toString()).put("name", a.name())
          .put("label", a.label()).put("changesThings", a.sideEffecting()).put("enabled", a.enabled());
    }
    ArrayNode triggers = out.putArray("triggers");
    for (Trigger t : detail.triggers()) {
      triggers.addObject().put("triggerId", t.id().toString()).put("name", t.name())
          .put("kind", t.kind()).put("webhookUrl", t.webhookUrl());
    }
    return out;
  }

  /** A thing found by its id or machine name, or a plain error naming what there is. */
  private static <T> T find(List<T> all, String ref, Function<T, UUID> idOf,
                            Function<T, String> nameOf, String what) {
    for (T t : all) {
      if (idOf.apply(t).toString().equals(ref) || nameOf.apply(t).equals(ref)) return t;
    }
    throw new ValidationException("No " + what + " '" + ref + "' on this service"
        + (all.isEmpty() ? "" : "; it has " + String.join(", ", all.stream().map(nameOf).toList())));
  }

  static String editorPath(UUID serviceId, String tab) {
    return "/connectors/" + serviceId + (tab == null ? "" : "/" + tab);
  }

  private static AuthScheme authOf(String auth) {
    try {
      return AuthScheme.of(auth.isBlank() ? "no_auth" : auth);
    } catch (IllegalArgumentException e) {
      throw new ValidationException("Unknown sign-in kind '" + auth + "'");
    }
  }

  private static UUID id(JsonNode args, String field) {
    String value = args.path(field).asText("");
    if (value.isBlank()) throw new ValidationException("'" + field + "' is required");
    return UUID.fromString(value);
  }

  private static String text(JsonNode args, String field) {
    String value = args.path(field).asText("").strip();
    return value.isEmpty() ? null : value;
  }

  private ConnectorServiceUseCase use() {
    return services.getObject();
  }

  private Optional<AgentDto> jizza(UUID agentId) {
    return agents.byId(agentId).filter(AgentDto::orchestrator);
  }

  private static ToolSpec spec(String name, String description, Map<String, ? extends JsonNode> props,
                               List<String> required) {
    // Listing and drafting stay in view so Jizza knows it can build; the rest are found by search.
    boolean entry = LIST.equals(name) || DRAFT.equals(name);
    return new ToolSpec(name, description,
        JsonSchemas.object(new LinkedHashMap<>(props), required), false, !entry);
  }

  private static Map<String, JsonNode> props(Object... pairs) {
    Map<String, JsonNode> map = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) map.put((String) pairs[i], (JsonNode) pairs[i + 1]);
    return map;
  }

  private static Map<String, JsonNode> tryProps() {
    return props(
        "serviceId", JsonSchemas.string("The service"),
        "action", JsonSchemas.string("The action's id or machine name"),
        "input", JsonSchemas.objectSchema("The action's inputs, by name"));
  }

  private static ObjectNode inputsSchema() {
    ObjectNode item = JsonSchemas.object(new LinkedHashMap<>(props(
        "name", JsonSchemas.string("Input name, as the API calls it"),
        "in", enumOf("Where it goes", List.of("path", "query", "header")),
        "type", enumOf("Its type", List.of("string", "integer", "number", "boolean")),
        "required", JsonSchemas.bool("Must be given"),
        "description", JsonSchemas.string("What it is"))), List.of("name"));
    ObjectNode node = Json.obj().put("type", "array")
        .put("description", "The request's inputs, apart from a JSON body");
    node.set("items", item);
    return node;
  }

  private static ObjectNode enumOf(String description, List<String> values) {
    ObjectNode node = JsonSchemas.string(description);
    ArrayNode allowed = node.putArray("enum");
    values.forEach(allowed::add);
    return node;
  }
}
