package com.jizza.engine.workflow.nodes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.autonomy.AutonomyGate;
import com.jizza.engine.api.autonomy.Decision;
import com.jizza.engine.api.autonomy.GuardrailContext;
import com.jizza.engine.api.device.DeviceActionCatalog;
import com.jizza.engine.api.device.DeviceActionSpec;
import com.jizza.engine.api.device.DeviceCommandDto;
import com.jizza.engine.api.device.DeviceCommandRequest;
import com.jizza.engine.api.device.DeviceCommands;
import com.jizza.engine.api.device.DeviceTarget;
import com.jizza.engine.api.enums.NodeType;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.SandboxViolationException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.webauto.LocatorFixes;
import com.jizza.engine.persistence.entity.AgentEntity;
import com.jizza.engine.persistence.repository.AgentRepository;
import com.jizza.engine.workflow.engine.NodeExecutor;
import com.jizza.engine.workflow.engine.RunContext;
import com.jizza.engine.workflow.sandbox.JexlSandbox;
import com.jizza.engine.workflow.sandbox.Templates;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Runs one action on one of the person's paired devices: {@code action} (a catalog name such as
 * {@code browser.extract}), optional {@code version}, {@code target} (a device id, {@code "any"},
 * {@code "phone"}, or an object with a tag or capability), {@code inputs} whose text values are
 * templates, and an optional {@code timeoutSec}.
 *
 * <p>The guardrails judge it with the catalog's action type, as an MCP tool is judged. The command
 * is submitted once and its id kept in the step's state, so a restart reads the same command; the
 * path then waits, and the command finishing wakes it. A device that failed or was not reachable
 * is a retryable failure, so the step's retry policy sends a fresh command; a refusal is final.
 */
@Component
public class DeviceActionNodeExecutor implements NodeExecutor {

  private final ObjectProvider<DeviceCommands> commands;
  private final ObjectProvider<DeviceActionCatalog> catalog;
  private final AutonomyGate autonomyGate;
  private final JexlSandbox sandbox;
  private final AgentRepository agents;
  private final ObjectProvider<LocatorFixes> fixes;
  private FastReplay fastReplay = new FastReplay(false);

  public DeviceActionNodeExecutor(ObjectProvider<DeviceCommands> commands,
                                  ObjectProvider<DeviceActionCatalog> catalog,
                                  AutonomyGate autonomyGate, JexlSandbox sandbox,
                                  AgentRepository agents, ObjectProvider<LocatorFixes> fixes) {
    this.commands = commands;
    this.catalog = catalog;
    this.autonomyGate = autonomyGate;
    this.sandbox = sandbox;
    this.agents = agents;
    this.fixes = fixes;
  }

  /** Off until the browser extension ships {@code browser.runSteps}; see {@link FastReplay}. */
  @org.springframework.beans.factory.annotation.Value("${jizza.webauto.fast-replay:false}")
  public void setFastReplay(boolean on) {
    this.fastReplay = new FastReplay(on);
  }

  @Override
  public NodeType type() { return NodeType.DEVICE_ACTION; }

  @Override
  public Outcome execute(Execution execution) {
    DeviceCommands broker = commands.getIfAvailable();
    DeviceActionCatalog actions = catalog.getIfAvailable();
    if (broker == null || actions == null) {
      return Outcome.permanentFailure("Devices are not available here");
    }
    ObjectNode state = execution.context().state(execution.node().id());
    if (state.has(FastReplay.BATCH_OUTPUT)) {
      // An earlier step sent this one in its batch; the browser has already done it.
      return Outcome.completed(state.get(FastReplay.BATCH_OUTPUT));
    }
    if (state.hasNonNull(FastReplay.BATCH_COMMAND)) {
      Outcome batched = fastReplay.follow(execution, broker);
      if (batched != null) return batched;
    } else if (fastReplay.enabled() && !state.has("commandId")) {
      Outcome batched = fastReplay.start(execution, broker, actions, autonomyGate,
          actorFor(execution), this::render);
      if (batched != null) return batched;
    }
    if (state.hasNonNull("commandId")) {
      return follow(execution, broker, state, UUID.fromString(state.get("commandId").asText()));
    }

    JsonNode config = execution.node().config();
    if (config == null || !config.hasNonNull("action")) {
      return Outcome.permanentFailure("Choose what this step does on the device");
    }
    int version = config.path("version").asInt(0);
    String action = config.get("action").asText();
    Optional<DeviceActionSpec> found = actions.resolve(action, version);
    if (found.isEmpty()) {
      return Outcome.permanentFailure("Devices have no action called '" + action + "'");
    }
    DeviceActionSpec spec = found.get();
    boolean browserAction = "browser".equals(spec.capability());
    String configuredTarget = config.path("target").asText("sameBrowser");
    RunContext.Browser browser = browserAction && "sameBrowser".equals(configuredTarget)
        ? execution.context().browser() : null;
    DeviceTarget target;
    try {
      if (browser != null) {
        target = DeviceTarget.device(browser.deviceId());
      } else if (browserAction && ("sameBrowser".equals(configuredTarget)
          || config.get("target") == null || config.path("target").isNull()
          || config.path("target").isTextual()
              && (configuredTarget.isBlank() || "any".equals(configuredTarget)))) {
        target = new DeviceTarget(null, null, "browser", false);
      } else {
        target = DeviceTarget.from(config.get("target"));
      }
    } catch (IllegalArgumentException e) {
      return Outcome.permanentFailure("The device this step names is not a device id");
    }
    UUID workspaceId = browser == null ? null : browser.workspaceId();
    if (config.hasNonNull("workspaceId")) {
      try {
        workspaceId = UUID.fromString(config.get("workspaceId").asText());
      } catch (IllegalArgumentException e) {
        return Outcome.permanentFailure("'workspaceId' is not an account id");
      }
    }
    if (spec.extension()) {
      // A tool a device added itself is described by that device, and typed as its owner said.
      spec = broker.describe(new DeviceCommandRequest(execution.userId(), workspaceId, target,
          action, version, null, null, execution.runId(), execution.node().id(), null, false))
          .orElse(spec);
    }

    JsonNode inputs;
    try {
      inputs = render(config.path("inputs"), execution.context().variables());
    } catch (SandboxViolationException | IllegalArgumentException e) {
      return Outcome.permanentFailure("The step's inputs could not be prepared: " + e.getMessage());
    }
    if (!inputs.isObject()) return Outcome.permanentFailure("The step's inputs must be an object");
    if (browserAction && browser != null && !config.path("inputs").has("session")) {
      ((ObjectNode) inputs).put("session", browser.sessionId());
    }
    if ("browser.open".equals(spec.action()) && execution.context().needsNewBrowserSession()) {
      ((ObjectNode) inputs).put("in", "newSession");
      ((ObjectNode) inputs).remove("session");
    }

    if (browserAction) WebStepForm.normalize(spec.action(), (ObjectNode) inputs);
    String nodeId = execution.node().id();
    WebStepForm.apply((ObjectNode) inputs, execution.context().approvedInputs(nodeId),
        offered(state));

    UUID actor = actorFor(execution);
    if (actor == null) {
      return Outcome.permanentFailure("No agent is available to run " + spec.title()
          + " under your guardrails");
    }
    String toolRef = toolRef(target, spec);
    GuardrailContext guardrail = execution.workflowId() == null
        ? new GuardrailContext(actor, toolRef, inputs, null, null, null, false, spec.actionType())
        : GuardrailContext.inWorkflow(actor, toolRef, inputs, execution.workflowId(),
            execution.workflowVersion(), nodeId, spec.actionType());
    boolean approved = execution.context().approved(nodeId);

    // A choice made at run time: the page's list as it is now, read before anyone decides.
    boolean pick = WebStepForm.CLICK_ITEM.equals(spec.action())
        && !"index".equals(inputs.path("by").asText("text"));
    List<String> choices = null;
    if (pick) {
      if (!state.has("choices")) {
        Decision first = autonomyGate.evaluate(guardrail);
        if (first.denied()) {
          return Outcome.permanentFailure("Blocked by your guardrails: " + first.reason());
        }
        return listChoices(execution, broker, state, target, workspaceId, inputs);
      }
      choices = new java.util.ArrayList<>();
      for (JsonNode label : state.get("choices")) choices.add(label.asText());
    }

    Decision decision;
    String chosen = pick ? WebStepForm.match(choices, inputs.path("item").asText(null)) : null;
    if (pick && chosen != null) ((ObjectNode) inputs).put("item", chosen);
    if (approved) {
      decision = autonomyGate.evaluate(guardrail);
    } else if (pick) {
      // "Let Jizza decide" picks the item the run asked for when the page lists it; otherwise
      // the person picks from what is there.
      decision = autonomyGate.evaluate(guardrail);
      if ((decision.allowed() || decision.needsJizza()) && chosen != null) {
        decision = decision.withRule(com.jizza.engine.api.enums.AutonomyRule.ALLOW,
            "the page lists " + chosen);
      } else if (decision.allowed() || decision.needsJizza()) {
        String wanted = inputs.path("item").asText("");
        decision = decision.withRule(com.jizza.engine.api.enums.AutonomyRule.ASK, wanted.isBlank()
            ? "Choose which one; the step was not told which"
            : "The page does not list \"" + wanted + "\" now, so choose one of what it lists");
      }
    } else {
      decision = autonomyGate.decide(guardrail);
    }
    if (decision.denied()) {
      return Outcome.permanentFailure("Blocked by your guardrails: " + decision.reason());
    }

    List<WebStepForm.Field> form = browserAction
        ? WebStepForm.fields(spec, config, (ObjectNode) inputs, choices) : List.of();
    if (pick && chosen == null && !form.isEmpty()) {
      // An item the page does not list cannot be clicked: it counts as not filled in.
      form = form.stream().map(f -> "item".equals(f.key())
          ? new WebStepForm.Field(f.key(), f.label(), f.type(), null, true, f.source(), f.options())
          : f).toList();
    }
    List<String> missing = form.stream().filter(WebStepForm.Field::missing)
        .map(WebStepForm.Field::label).toList();
    boolean asks = !approved && (decision.needsPermission() || decision.needsJizza());
    if (asks || !missing.isEmpty()) {
      String why = !missing.isEmpty() && !asks
          ? (missing.size() == 1 ? missing.get(0) + " has" : String.join(", ", missing) + " have")
              + " no value, so fill " + (missing.size() == 1 ? "it" : "them") + " in to go on"
          : decision.reason();
      state.remove("choices");
      ArrayNode keys = state.putArray("offered");
      form.forEach(f -> keys.add(f.key()));
      return Outcome.awaitPermission("This step needs your approval before it asks "
          + target.describe() + " to " + lower(spec.title()) + ". " + why + ".",
          approvalDetail(spec, form, choices, execution.context().variables()));
    }
    state.remove("choices");
    Duration timeout = config.path("timeoutSec").asInt(0) > 0
        ? Duration.ofSeconds(config.get("timeoutSec").asInt()) : null;
    DeviceCommandDto command;
    try {
      command = broker.submit(new DeviceCommandRequest(execution.userId(), workspaceId, target,
          spec.action(), spec.version(), inputs, timeout, execution.runId(),
          nodeId, execution.runId() + ":" + nodeId + ":" + execution.attempt(), false));
    } catch (ValidationException | NotFoundException e) {
      return Outcome.permanentFailure(e.getMessage());
    }
    state.put("commandId", command.id().toString());
    state.put("device", command.deviceName());
    state.put("browserAction", browserAction);
    if (browserAction) {
      if (workspaceId != null) state.put("browserWorkspace", workspaceId.toString());
      boolean opensNewSession = "browser.open".equals(spec.action())
          && "newSession".equals(inputs.path("in").asText());
      String requestedSession = inputs.path("session").isTextual()
          ? inputs.get("session").asText() : null;
      boolean reusesAffinity = browser != null && browser.sessionId().equals(requestedSession);
      state.put("browserOpened", opensNewSession
          || requestedSession == null && browser == null
          || reusesAffinity && browser.opened());
    }
    return follow(execution, broker, state, command.id());
  }

  /** The keys the approval card offered, which are the only ones the person's answer may set. */
  private static java.util.Set<String> offered(ObjectNode state) {
    java.util.Set<String> keys = new java.util.HashSet<>();
    state.path("offered").forEach(k -> keys.add(k.asText()));
    return keys;
  }

  /** What the approval card shows: the step's values, what is missing, and data to map from. */
  private static ObjectNode approvalDetail(DeviceActionSpec spec, List<WebStepForm.Field> form,
                                          List<String> choices, Map<String, Object> variables) {
    ObjectNode detail = Json.obj();
    if (form.isEmpty()) return detail;
    ObjectNode out = detail.putObject("form").put("action", spec.action())
        .put("title", spec.title());
    ArrayNode fields = out.putArray("fields");
    form.forEach(f -> fields.add(f.toJson()));
    ArrayNode missing = out.putArray("missing");
    form.stream().filter(WebStepForm.Field::missing).forEach(f -> missing.add(f.key()));
    if (choices != null) out.put("choicesAt", java.time.Instant.now().toString());
    out.set("data", WebStepForm.data(variables));
    return detail;
  }

  /**
   * Reads the list the step chooses from as the page shows it now. The listing is a read on the
   * same page and browser; its result wakes the step, which then decides or asks.
   */
  private Outcome listChoices(Execution execution, DeviceCommands broker, ObjectNode state,
                              DeviceTarget target, UUID workspaceId, JsonNode inputs) {
    if (state.hasNonNull("listCommandId")) {
      DeviceCommandDto listing = broker.find(UUID.fromString(state.get("listCommandId").asText()))
          .orElse(null);
      if (listing == null) {
        state.remove("listCommandId");
        return Outcome.retryable("The list read from the page is gone");
      }
      if (!listing.status().finished()) return Outcome.awaitDevice(listing.deadlineAt());
      state.remove("listCommandId");
      if (listing.status() != com.jizza.engine.api.device.DeviceCommandStatus.SUCCEEDED) {
        preserveError(state, listing);
        return Outcome.retryable("Could not read the list on the page: "
            + (listing.error() == null ? listing.status().name().toLowerCase() : listing.error()));
      }
      ArrayNode labels = state.putArray("choices");
      WebStepForm.labels(listing.output()).forEach(labels::add);
      if (labels.isEmpty()) {
        state.remove("choices");
        return Outcome.retryable("The list on the page has no items to choose from");
      }
      return execute(execution);
    }
    ObjectNode listInput = Json.obj();
    for (String key : List.of("session", "element", "timeoutMs", "highlight")) {
      if (inputs.has(key)) listInput.set(key, inputs.get(key));
    }
    DeviceCommandDto listing;
    try {
      listing = broker.submit(new DeviceCommandRequest(execution.userId(), workspaceId, target,
          "browser.listItems", 1, listInput, null, execution.runId(), execution.node().id(),
          execution.runId() + ":" + execution.node().id() + ":" + execution.attempt() + ":list"
              + state.path("lists").asInt(0),
          false));
    state.put("lists", state.path("lists").asInt(0) + 1);
    } catch (ValidationException | NotFoundException e) {
      return Outcome.permanentFailure(e.getMessage());
    }
    state.put("listCommandId", listing.id().toString());
    if (listing.status().finished()) return listChoices(execution, broker, state, target,
        workspaceId, inputs);
    return Outcome.awaitDevice(listing.deadlineAt());
  }

  /** Whose guardrails judge the step: the workflow's agent, or the person's Jizza. */
  private UUID actorFor(Execution execution) {
    return execution.owningAgentId() != null ? execution.owningAgentId()
        : agents.findByUserIdAndIsOrchestratorTrue(execution.userId()).map(AgentEntity::getId)
            .orElse(null);
  }

  private Outcome follow(Execution execution, DeviceCommands broker, ObjectNode state,
                         UUID commandId) {
    DeviceCommandDto command = broker.find(commandId).orElse(null);
    if (command == null) {
      state.remove("commandId");
      return Outcome.retryable("The command sent to the device is gone");
    }
    if (!command.status().finished()) return Outcome.awaitDevice(command.deadlineAt());
    return switch (command.status()) {
      case SUCCEEDED -> Outcome.completed(outputOf(command,
          state.path("browserAction").asBoolean(false),
          state.path("browserOpened").asBoolean(true),
          state.path("browserWorkspace").asText(null)));
      case FAILED -> {
        // The next attempt sends a new command rather than reading this one again.
        state.remove("commandId");
        preserveError(state, command);
        String kind = command.output() == null ? null
            : command.output().path("error").path("kind").asText(null);
        String message = failureMessage(command);
        if (state.path("browserAction").asBoolean(false) && "element_not_found".equals(kind)) {
          proposeFix(execution, command.output().path("error"));
        }
        yield state.path("browserAction").asBoolean(false) && kind != null
            && PERMANENT_BROWSER_FAILURES.contains(kind)
            ? Outcome.permanentFailure(message) : Outcome.retryable(message);
      }
      case EXPIRED -> {
        state.remove("commandId");
        preserveError(state, command);
        yield Outcome.retryable(failureMessage(command));
      }
      default -> Outcome.permanentFailure(command.explain());
    };
  }

  /** What the device returned, with which device did it and the files it sent back. */
  static JsonNode outputOf(DeviceCommandDto command, boolean browserAction,
                           boolean browserOpened, String browserWorkspace) {
    return stepOutput(command.output(), command.deviceId(), command.deviceName(), command.files(),
        command.action(), command.input(), browserAction, browserOpened, browserWorkspace);
  }

  /**
   * One step's output as later steps and the run's browser affinity read it. A batch maps each of
   * its steps through here too, so a step's output is the same however it was sent.
   */
  static JsonNode stepOutput(JsonNode raw, UUID deviceId, String deviceName,
                             java.util.List<UUID> files, String action, JsonNode input,
                             boolean browserAction, boolean browserOpened,
                             String browserWorkspace) {
    ObjectNode out = raw != null && raw.isObject() ? ((ObjectNode) raw).deepCopy() : Json.obj();
    out.remove(java.util.List.of("_browser", "_browserOpened", "_browserClear", "_browserAction"));
    if (raw != null && !raw.isObject() && !raw.isNull()) {
      out.set("value", raw);
    }
    out.putObject("device").put("id", deviceId.toString()).put("name", deviceName);
    if (files != null && !files.isEmpty()) {
      var list = out.putArray("files");
      files.forEach(f -> list.add(f.toString()));
    }
    if (browserAction) {
      out.put("_browserAction", action);
      if ("browser.open".equals(action) && out.path("sessionId").isTextual()) {
        out.putObject("_browser").put("deviceId", deviceId.toString())
            .put("sessionId", out.get("sessionId").asText());
        if (browserWorkspace != null) {
          ((ObjectNode) out.get("_browser")).put("workspaceId", browserWorkspace);
        }
        out.put("_browserOpened", browserOpened);
      } else if ("browser.close".equals(action)
          && !"tab".equals(input.path("what").asText("session"))
          && input.path("session").isTextual()) {
        out.putObject("_browserClear").put("deviceId", deviceId.toString())
            .put("sessionId", input.get("session").asText());
      }
    }
    return out;
  }

  /**
   * A page change: the browser could not find the step's element and suggested others. The person
   * is offered a fix to approve; a fix that cannot be kept never changes how this step fails.
   */
  private void proposeFix(Execution execution, JsonNode error) {
    LocatorFixes service = fixes.getIfAvailable();
    if (service == null || execution.workflowId() == null
        || !error.path("candidates").isArray() || error.path("candidates").isEmpty()) {
      return;
    }
    try {
      service.propose(new LocatorFixes.FailedStep(execution.userId(), execution.workflowId(),
          execution.workflowVersion(), execution.runId(), execution.node().id(), error));
    } catch (RuntimeException e) {
      org.slf4j.LoggerFactory.getLogger(DeviceActionNodeExecutor.class)
          .warn("Could not keep a fix for step {} of run {}", execution.node().id(),
              execution.runId(), e);
    }
  }

  private static final java.util.Set<String> PERMANENT_BROWSER_FAILURES = java.util.Set.of(
      "element_not_found", "element_ambiguous", "not_actionable", "site_not_allowed",
      "bad_input", "secret_missing", "no_session", "cancelled_by_person");

  private static void preserveError(ObjectNode state, DeviceCommandDto command) {
    ObjectNode error = command.output() != null && command.output().path("error").isObject()
        ? ((ObjectNode) command.output().path("error")).deepCopy() : Json.obj();
    // Which device kept the screenshot, so the run history can show it.
    if (command.deviceId() != null) error.put("deviceId", command.deviceId().toString());
    if (command.files() != null && !command.files().isEmpty()) {
      var screenshots = error.putArray("files");
      command.files().forEach(id -> screenshots.add(id.toString()));
    }
    state.set("error", error);
  }

  private static String failureMessage(DeviceCommandDto command) {
    String message = command.deviceName() + " could not do it";
    if (command.error() != null && !command.error().isBlank()) message += ": " + command.error();
    JsonNode error = command.output() == null ? null : command.output().path("error");
    String url = error == null ? null : error.path("url").asText(null);
    if (url == null && command.output() != null) url = command.output().path("url").asText(null);
    return url == null || url.isBlank() ? message : message + " on " + url;
  }

  /** As the chat tool for the same device is named, so one guardrail covers both. */
  static String toolRef(DeviceTarget target, DeviceActionSpec spec) {
    String who = target.deviceId() != null ? target.deviceId().toString().substring(0, 8)
        : target.phone() ? "phone" : "any";
    return "device_" + who + "__" + spec.action().replaceAll("[^A-Za-z0-9]", "_");
  }

  private static String lower(String title) {
    return title.isEmpty() ? title : Character.toLowerCase(title.charAt(0)) + title.substring(1);
  }

  /** Text values are templates; everything else is taken as written. */
  JsonNode render(JsonNode value, Map<String, Object> variables) {
    if (value == null || value.isMissingNode() || value.isNull()) return Json.obj();
    if (value.isTextual()) return Templates.renderValue(value.asText(), sandbox, variables);
    if (value.isObject()) {
      ObjectNode out = Json.obj();
      value.properties().forEach(e -> {
        JsonNode rendered = render(e.getValue(), variables);
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
}
