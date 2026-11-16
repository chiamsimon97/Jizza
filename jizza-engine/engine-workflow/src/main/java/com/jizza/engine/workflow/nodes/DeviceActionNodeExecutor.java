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
import com.jizza.engine.persistence.repository.WorkflowStorageRepository;
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
  private final ObjectProvider<WorkflowStorageRepository> storage;
  private FastReplay fastReplay = new FastReplay(false);

  /** Failures an optional step treats as "not there this time" and skips. */
  static final java.util.Set<String> SKIPPABLE_WHEN_OPTIONAL = java.util.Set.of(
      "element_not_found", "not_actionable", "item_not_found", "frame_not_found");

  public DeviceActionNodeExecutor(ObjectProvider<DeviceCommands> commands,
                                  ObjectProvider<DeviceActionCatalog> catalog,
                                  AutonomyGate autonomyGate, JexlSandbox sandbox,
                                  AgentRepository agents, ObjectProvider<LocatorFixes> fixes,
                                  ObjectProvider<WorkflowStorageRepository> storage) {
    this.commands = commands;
    this.storage = storage;
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
    JsonNode nodeConfig = execution.node().config();
    if (nodeConfig != null && nodeConfig.path("disabled").asBoolean(false)) {
      // Turned off while editing a recording: kept in the steps, never sent.
      return Outcome.completed(Json.obj().put("skipped", true).put("reason", "turned off"));
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
        // The browser the person chose when they started the run, else whichever is free.
        UUID preferred = execution.context().preferredBrowser();
        target = preferred != null ? DeviceTarget.device(preferred)
            : new DeviceTarget(null, null, "browser", false);
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
    // The details the person attached to this step: their defaults, then their answers below.
    JsonNode detailBlocks = config.path("details").path("blocks");
    boolean detailStep = detailBlocks.isArray() && !detailBlocks.isEmpty();
    if (detailStep && !inputs.path("details").isObject()) {
      ObjectNode seeded = ((ObjectNode) inputs).putObject("details");
      for (JsonNode block : detailBlocks) {
        String name = WebStepForm.detailName(block);
        if (name == null || !block.hasNonNull("value")) continue;
        JsonNode value = render(block.get("value"), execution.context().variables());
        if (value.isValueNode() && !value.isNull() && !value.asText().isEmpty()) {
          seeded.set(name, value);
        }
      }
    }
    String nodeId = execution.node().id();
    WebStepForm.apply((ObjectNode) inputs, execution.context().approvedInputs(nodeId),
        offered(state));

    if (UPLOAD_FILE.equals(spec.action())) {
      String problem = handOver(execution, (ObjectNode) inputs, target, state);
      if (problem != null) return Outcome.permanentFailure(problem);
    }

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
    boolean formAction = browserAction || "ui".equals(spec.capability());
    JsonNode edits = execution.context().approvedInputs(nodeId);

    // A choice made at run time: the page's list as it is now, read before anyone decides.
    String pickKey = WebStepForm.pickKey(spec.action());
    boolean dropdown = WebStepForm.SELECT_OPTION.equals(spec.action());
    // A dropdown marked dynamic is read from the page each run; a static one chooses from the
    // items read while recording.
    boolean listed = WebStepForm.CLICK_ITEMS.contains(spec.action())
        && !"index".equals(inputs.path("by").asText("text"))
        || dropdown && "dynamic".equals(inputs.path("mode").asText(""));
    if (dropdown && !listed && !state.has("choices")) {
      List<String> known = WebStepForm.optionNames(inputs.path("options"));
      if (known != null) {
        ArrayNode seeded = state.putArray("choices");
        for (int i = 0; i < known.size() && i < 200; i++) {
          seeded.addObject().put("label", known.get(i)).put("text", known.get(i)).put("index", i);
        }
      }
    }
    boolean pick = pickKey != null && (listed || state.has("choices"));
    // A picture of each listed item, taken with the list, so the card can show the one chosen.
    boolean itemShots = config.path("screenshots").asBoolean(false);
    String prefer = inputs.path("prefer").asText(null);
    List<ItemMatch.Item> choices = null;
    if (pick) {
      JsonNode within = inputs.path("within");
      if (listed && within.isObject() && !state.has("parent")) {
        if (!state.has("parents")) {
          Decision first = autonomyGate.evaluate(guardrail);
          if (first.denied()) {
            return Outcome.permanentFailure("Blocked by your guardrails: " + first.reason());
          }
          ObjectNode parentInput = listInput(inputs, false);
          parentInput.set("element", within.get("element"));
          // The cards' own pattern (Mark a list), never the items'.
          parentInput.remove("itemCss");
          parentInput.remove("titleCss");
          if (within.hasNonNull("itemCss")) parentInput.set("itemCss", within.get("itemCss"));
          if (within.hasNonNull("titleCss")) parentInput.set("titleCss", within.get("titleCss"));
          if (itemShots) parentInput.put("captureItems", true);
          return listChoices(execution, broker, state, target, workspaceId,
              WebStepForm.listAction(spec.action()), parentInput, "parents");
        }
        List<ItemMatch.Item> parents = ItemMatch.items(state.get("parents"));
        String wantedParent = within.path("item").asText("");
        String known = remembered(execution, "within", wantedParent, parents);
        ItemMatch.Pick parent = known != null ? new ItemMatch.Pick(known, List.of())
            : ItemMatch.match(parents, wantedParent, null);
        if (!parent.found()) {
          // Which card to choose inside is the person's call before anything else.
          state.put("wantedWithin", wantedParent);
          if (listed && !parents.isEmpty() && !state.path("groupsTried").asBoolean(false)) {
            // Read the items of every card first (through the first card), so the card the
            // person answers on lists the showtimes of whichever theatre they pick.
            state.put("groupsTried", true);
            state.put("parentUnmatched", true);
            state.put("parent", parents.get(0).label());
            ((ObjectNode) within).put("item", parents.get(0).label());
            ObjectNode through = listInput(inputs, true);
            if (itemShots) through.put("captureItems", true);
            return listChoices(execution, broker, state, target, workspaceId,
                WebStepForm.listAction(spec.action()), through, "choices");
          }
          List<WebStepForm.Field> form = WebStepForm.fields(spec, config, (ObjectNode) inputs, null)
              .stream().map(f -> "within.item".equals(f.key())
                  ? f.withOptions("select", ordered(parents, parent.tied()), true) : f).toList();
          form = withRemember(execution, form, wantedParent);
          return ask(execution, state, spec, target, form, labels(parents),
              pickReason(wantedParent, parent, "the list"));
        }
        String parentLabel = parents.stream().filter(i -> i.display().equals(parent.chosen()))
            .map(ItemMatch.Item::label).findFirst().orElse(parent.chosen());
        state.put("parent", parentLabel);
        state.put("parentDisplay", parent.chosen());
      }
      if (within.isObject() && state.has("parent")) {
        ((ObjectNode) within).put("item", state.get("parent").asText());
      }
      if (listed && !state.has("choices")) {
        if (!within.isObject()) {
          Decision first = autonomyGate.evaluate(guardrail);
          if (first.denied()) {
            return Outcome.permanentFailure("Blocked by your guardrails: " + first.reason());
          }
        }
        ObjectNode choicesInput = listInput(inputs, true);
        if (itemShots) choicesInput.put("captureItems", true);
        return listChoices(execution, broker, state, target, workspaceId,
            WebStepForm.listAction(spec.action()), choicesInput, "choices");
      }
      choices = ItemMatch.items(state.get("choices"));
      if (state.path("parentUnmatched").asBoolean(false)) {
        // The card asked for is not on the page: the person picks the card, and the items
        // follow it from the items read for every card.
        List<ItemMatch.Item> parents = ItemMatch.items(state.get("parents"));
        String wantedParent = state.path("wantedWithin").asText("");
        if (within.isObject()) ((ObjectNode) within).put("item", wantedParent);
        ItemMatch.Pick parent = ItemMatch.match(parents, wantedParent, null);
        List<WebStepForm.Field> form = WebStepForm.fields(spec, config, (ObjectNode) inputs,
                labels(choices)).stream()
            .map(f -> "within.item".equals(f.key())
                ? f.withOptions("select", ordered(parents, parent.tied()), true) : f)
            .toList();
        form = withRemember(execution, form, wantedParent);
        return ask(execution, state, spec, target, form, labels(parents),
            pickReason(wantedParent, parent, "the list"));
      }
    }

    String wanted = !pick ? null : inputs.path(pickKey).asText(
        WebStepForm.CHOOSE_OPTION.equals(spec.action()) ? inputs.path("text").asText("") : "");
    ItemMatch.Pick picked = null;
    if (pick) {
      String known = remembered(execution, pickKey, wanted, choices);
      picked = known != null ? new ItemMatch.Pick(known, List.of())
          : ItemMatch.match(choices, wanted, prefer);
      if (picked.found()) {
        String chosenName = picked.chosen();
        ItemMatch.Item item = choices.stream().filter(i -> i.display().equals(chosenName))
            .findFirst().orElse(null);
        if (item != null && item.byIndex() && WebStepForm.CLICK_ITEMS.contains(spec.action())) {
          // Two cards share the name: the one meant is clicked by its place in the list.
          ((ObjectNode) inputs).put("by", "index").put(pickKey, String.valueOf(item.index()));
          state.put("chosenDisplay", item.display());
        } else {
          ((ObjectNode) inputs).put(pickKey, item == null ? picked.chosen() : item.label());
        }
      }
    }
    rememberAnswer(execution, state, edits, pickKey, inputs);

    // The site refused the form it was sent: the person fixes the values it named.
    JsonNode siteErrors = state.get("siteErrors");
    boolean fixing = siteErrors != null && !state.path("siteErrorsAsked").asBoolean(false);
    if (siteErrors != null && !fixing) {
      state.remove("siteErrors");
      state.remove("siteErrorsAsked");
    }

    Decision decision;
    if (approved) {
      decision = autonomyGate.evaluate(guardrail);
    } else if (pick) {
      // "Let Jizza decide" picks the item the run asked for when the page lists it; otherwise
      // the person picks from what is there. That is a plain match against the page, so it
      // holds even when Jizza itself runs the step.
      decision = autonomyGate.evaluate(guardrail);
      boolean letDecide = decision.needsJizza() || decision.jizzaActs();
      if ((decision.allowed() || letDecide) && picked.found()) {
        decision = decision.withRule(com.jizza.engine.api.enums.AutonomyRule.ALLOW,
            "the page lists " + picked.chosen());
      } else if (decision.allowed() || letDecide) {
        decision = decision.withRule(com.jizza.engine.api.enums.AutonomyRule.ASK,
            pickReason(wanted, picked, "the page"));
      }
    } else {
      decision = autonomyGate.decide(guardrail);
    }
    if (decision.denied()) {
      return Outcome.permanentFailure("Blocked by your guardrails: " + decision.reason());
    }

    List<WebStepForm.Field> form = formAction
        ? WebStepForm.fields(spec, config, (ObjectNode) inputs, pick ? labels(choices) : null)
        : List.of();
    List<WebStepForm.Field> details = formAction && detailStep
        ? WebStepForm.detailFields(config, inputs) : List.of();
    if (!details.isEmpty()) {
      List<WebStepForm.Field> joined = new java.util.ArrayList<>(form);
      joined.addAll(details);
      form = joined;
    }
    if (pick && state.has("parents") && state.hasNonNull("parentDisplay")) {
      // The card chosen inside (the theatre) stays a choice from the page's cards, so the person
      // can switch it on the card; the items follow it (optionsBy).
      List<String> cards = labels(ItemMatch.items(state.get("parents")));
      com.fasterxml.jackson.databind.node.TextNode chosenCard =
          Json.mapper().getNodeFactory().textNode(state.get("parentDisplay").asText());
      form = form.stream().map(f -> "within.item".equals(f.key())
          ? new WebStepForm.Field(f.key(), f.label(), "select", chosenCard, true, f.source(),
              cards, f.error())
          : f).toList();
    }
    if (pick && !picked.found() && !form.isEmpty()) {
      // An item the page does not list cannot be clicked: it counts as not filled in.
      List<String> options = ordered(choices, picked.tied());
      form = form.stream().map(f -> pickKey.equals(f.key())
          ? f.withOptions("select", options, true) : f).toList();
      state.put("wanted", wanted);
      form = withRemember(execution, form, wanted);
    }
    String siteSaid = null;
    if (fixing) {
      java.util.Map<Integer, String> byField = new java.util.HashMap<>();
      List<String> general = new java.util.ArrayList<>();
      for (JsonNode error : siteErrors) {
        String message = error.path("message").asText("").strip();
        if (message.isEmpty()) continue;
        if (error.path("field").isInt()) byField.merge(error.get("field").asInt(), message, (a, b) -> a + " " + b);
        else general.add(error.path("label").asText("").isBlank() ? message
            : error.get("label").asText() + ": " + message);
      }
      form = form.stream().map(f -> {
        for (var e : byField.entrySet()) {
          if (f.key().equals("fields." + e.getKey() + ".value")) return f.withError(e.getValue());
        }
        return f;
      }).toList();
      List<String> all = new java.util.ArrayList<>(general);
      byField.forEach((i, m) -> all.add(m));
      siteSaid = "The site did not accept the form" + (all.isEmpty() ? "" : ": "
          + String.join("; ", all.subList(0, Math.min(all.size(), 5))))
          + ". Fix the values and approve to send it again";
      state.put("siteErrorsAsked", true);
    }
    List<String> missing = form.stream().filter(WebStepForm.Field::missing)
        .filter(f -> !f.key().startsWith("_"))
        .map(WebStepForm.Field::label).toList();
    boolean asks = !approved && (decision.needsPermission() || decision.needsJizza());
    // A step with details attached always stops: the person reads them and answers, then it
    // goes on with their answers as its details output.
    boolean asksDetails = !details.isEmpty() && !approved;
    if (asks || asksDetails || fixing || !missing.isEmpty()) {
      String why = fixing ? siteSaid
          : pick && !picked.found() ? pickReason(wanted, picked, "the page")
          : !missing.isEmpty() && !asks
          ? (missing.size() == 1 ? missing.get(0) + " has" : String.join(", ", missing) + " have")
              + " no value, so fill " + (missing.size() == 1 ? "it" : "them") + " in to go on"
          : asksDetails && !asks ? "Answer what it asks for to go on"
          : decision.reason();
      return ask(execution, state, spec, target, form, pick ? labels(choices) : null, why);
    }
    state.remove("choices");
    // The answers to the step's details are its own output, never sent to the device.
    JsonNode detailAnswers = ((ObjectNode) inputs).remove("details");
    if (detailAnswers != null && detailAnswers.isObject() && !detailAnswers.isEmpty()) {
      state.set("details", detailAnswers);
    }
    Duration timeout = config.path("timeoutSec").asInt(0) > 0
        ? Duration.ofSeconds(config.get("timeoutSec").asInt()) : null;
    DeviceCommandDto command;
    try {
      command = broker.submit(new DeviceCommandRequest(execution.userId(), workspaceId, target,
          spec.action(), spec.version(), inputs, timeout, execution.runId(),
          nodeId, execution.runId() + ":" + nodeId + ":" + execution.attempt()
              + (state.path("sends").asInt(0) > 0 ? ":s" + state.path("sends").asInt() : ""),
          false));
    } catch (ValidationException | NotFoundException e) {
      return Outcome.permanentFailure(e.getMessage());
    }
    state.put("sends", state.path("sends").asInt(0) + 1);
    state.remove(List.of("parent", "parents", "parentDisplay", "groups"));
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

  /**
   * The items of every card of the outer list, by the card's name as the card's choice shows it
   * (the showtimes of each theatre), when the page listed them; null otherwise.
   */
  static ObjectNode optionsByCard(ObjectNode state, String pickKey, List<WebStepForm.Field> form) {
    if (pickKey == null || !state.path("groups").isArray() || !state.has("parents")
        || form.stream().noneMatch(f -> "within.item".equals(f.key()) && f.options() != null)) {
      return null;
    }
    List<ItemMatch.Item> cards = ItemMatch.items(state.get("parents"));
    ObjectNode out = Json.obj();
    for (JsonNode group : state.get("groups")) {
      int index = group.path("index").asInt(-1);
      String label = group.path("label").asText("");
      String card = cards.stream().filter(c -> c.index() == index && c.label().equals(label))
          .map(ItemMatch.Item::display).findFirst()
          .orElse(cards.stream().filter(c -> c.label().equals(label))
              .map(ItemMatch.Item::display).findFirst().orElse(null));
      if (card == null || out.has(card)) continue;
      ArrayNode items = out.putArray(card);
      ItemMatch.items(group.path("items")).forEach(i -> items.add(i.display()));
    }
    return out.isEmpty() ? null : out;
  }

  /** The keys the approval card offered, which are the only ones the person's answer may set. */
  private static java.util.Set<String> offered(ObjectNode state) {
    java.util.Set<String> keys = new java.util.HashSet<>();
    state.path("offered").forEach(k -> keys.add(k.asText()));
    return keys;
  }

  /**
   * Stops for the person with the step's values. What the page listed is read again after the
   * answer, so the choice is still on the page when it is clicked.
   */
  private Outcome ask(Execution execution, ObjectNode state, DeviceActionSpec spec,
                      DeviceTarget target, List<WebStepForm.Field> form, List<String> choices,
                      String why) {
    ObjectNode byCard = optionsByCard(state, WebStepForm.pickKey(spec.action()), form);
    ObjectNode shots = itemShots(state);
    state.remove(List.of("choices", "parents", "parent", "parentDisplay", "groups",
        "parentUnmatched", "groupsTried"));
    ArrayNode keys = state.putArray("offered");
    form.forEach(f -> keys.add(f.key()));
    ObjectNode detail = approvalDetail(spec, form, choices, execution.context().variables());
    if (shots != null && detail.path("form").isObject()) {
      ((ObjectNode) detail.get("form")).set("shots", shots);
    }
    if (byCard != null && detail.path("form").path("fields").isArray()) {
      for (JsonNode field : detail.path("form").path("fields")) {
        if (field.path("key").asText().equals(WebStepForm.pickKey(spec.action()))) {
          ((ObjectNode) field).put("dependsOn", "within.item").set("optionsBy", byCard);
        }
      }
    }
    return Outcome.awaitPermission("This step needs your approval before it asks "
        + target.describe() + " to " + lower(spec.title()) + ". " + why + ".", detail);
  }

  /**
   * The pictures taken of the listed items while the list was read, option name to file id, with
   * the device that keeps them; null when none were taken. The card shows the picture of the
   * option chosen and switches it with the choice.
   */
  private static ObjectNode itemShots(ObjectNode state) {
    if (!state.hasNonNull("shotsDevice")) return null;
    ObjectNode byOption = Json.obj();
    for (String key : List.of("choices", "parents")) {
      JsonNode array = state.path(key);
      if (!array.isArray()) continue;
      java.util.Map<Integer, String> ids = new java.util.HashMap<>();
      for (JsonNode raw : array) {
        if (raw.path("shot").isTextual()) ids.put(raw.path("index").asInt(-1), raw.get("shot").asText());
      }
      if (ids.isEmpty()) continue;
      for (ItemMatch.Item item : ItemMatch.items(array)) {
        String id = ids.get(item.index());
        if (id != null && !byOption.has(item.display())) byOption.put(item.display(), id);
      }
    }
    if (byOption.isEmpty()) return null;
    ObjectNode out = Json.obj().put("device", state.get("shotsDevice").asText());
    out.set("byOption", byOption);
    return out;
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
    boolean listedNow = choices != null || form.stream().anyMatch(f -> f.options() != null
        && "select".equals(f.type()) && f.key().equals("within.item"));
    if (listedNow) out.put("choicesAt", java.time.Instant.now().toString());
    out.set("data", WebStepForm.data(variables));
    return detail;
  }

  /** The inputs the listing of a choosing step's list takes. */
  private static ObjectNode listInput(JsonNode inputs, boolean withinAndMore) {
    ObjectNode out = Json.obj();
    for (String key : List.of("session", "element", "timeoutMs", "highlight", "app", "max",
        "itemCss", "titleCss")) {
      if (inputs.has(key)) out.set(key, inputs.get(key));
    }
    if (withinAndMore) {
      for (String key : List.of("within", "loadMore")) {
        if (inputs.has(key)) out.set(key, inputs.get(key));
      }
      // The items of every card too, so the approval card can switch the card (theatre).
      if (inputs.path("within").isObject()) out.put("groups", true);
    }
    return out;
  }

  private static List<String> labels(List<ItemMatch.Item> items) {
    return items == null ? null : items.stream().map(ItemMatch.Item::display).toList();
  }

  /** All the listed labels, the ones that tied first. */
  private static List<String> ordered(List<ItemMatch.Item> items, List<String> tied) {
    java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>(tied);
    items.forEach(i -> out.add(i.display()));
    return new java.util.ArrayList<>(out);
  }

  private static String pickReason(String wanted, ItemMatch.Pick pick, String where) {
    if (wanted == null || wanted.isBlank()) return "Choose one of what it lists now";
    if (!pick.tied().isEmpty()) {
      List<String> some = pick.tied().subList(0, Math.min(pick.tied().size(), 4));
      return "Several items match \"" + wanted + "\" (" + String.join(", ", some)
          + (pick.tied().size() > some.size() ? ", …" : "") + "), so choose one";
    }
    return "The " + ("the page".equals(where) ? "page" : "list") + " does not list \"" + wanted
        + "\" now, so choose one of what it lists";
  }

  // ── remembered picks ────────────────────────────────────────────────────

  static final String REMEMBER = "_remember";

  private String picksKey(Execution execution) {
    return "jizza.picks." + execution.workflowId() + "." + execution.node().id();
  }

  /** Offers to remember the person's pick for this wording, for a saved workflow. */
  private List<WebStepForm.Field> withRemember(Execution execution, List<WebStepForm.Field> form,
                                               String wanted) {
    if (execution.workflowId() == null || storage.getIfAvailable() == null || wanted == null
        || ItemMatch.fold(wanted).isEmpty()) {
      return form;
    }
    List<WebStepForm.Field> out = new java.util.ArrayList<>(form);
    out.add(new WebStepForm.Field(REMEMBER, "Next time the run asks for \"" + wanted
        + "\", choose this one without asking", "checkbox",
        Json.mapper().getNodeFactory().booleanNode(false), false, null, null));
    return out;
  }

  /** The pick remembered for this wording, when the list still has it. */
  private String remembered(Execution execution, String kind, String wanted,
                            List<ItemMatch.Item> items) {
    WorkflowStorageRepository repo = storage.getIfAvailable();
    if (repo == null || execution.workflowId() == null || wanted == null
        || ItemMatch.fold(wanted).isEmpty()) {
      return null;
    }
    String label = repo.findByUserIdAndKey(execution.userId(), picksKey(execution))
        .map(row -> row.getValue() == null ? null
            : row.getValue().path(kind).path(ItemMatch.fold(wanted)).asText(null))
        .orElse(null);
    if (label == null) return null;
    return items.stream().anyMatch(i -> i.display().equals(label)) ? label : null;
  }

  /** Keeps the person's pick when they ticked "choose this one without asking". */
  private void rememberAnswer(Execution execution, ObjectNode state, JsonNode edits,
                              String pickKey, JsonNode inputs) {
    WorkflowStorageRepository repo = storage.getIfAvailable();
    if (repo == null || execution.workflowId() == null || edits == null
        || !WebStepForm.truthy(edits.get(REMEMBER)) || pickKey == null) {
      return;
    }
    java.util.Map<String, String[]> picks = new java.util.LinkedHashMap<>();
    if (state.hasNonNull("wanted") && edits.path(pickKey).isTextual()) {
      picks.put(pickKey, new String[] {state.get("wanted").asText(), edits.get(pickKey).asText()});
    }
    if (state.hasNonNull("wantedWithin") && edits.path("within.item").isTextual()) {
      picks.put("within", new String[] {state.get("wantedWithin").asText(),
          edits.get("within.item").asText()});
    }
    if (picks.isEmpty()) return;
    String key = picksKey(execution);
    var row = repo.findByUserIdAndKey(execution.userId(), key).orElseGet(() -> {
      var fresh = new com.jizza.engine.persistence.entity.WorkflowStorageEntity();
      fresh.setUserId(execution.userId());
      fresh.setKey(key);
      return fresh;
    });
    ObjectNode value = row.getValue() instanceof ObjectNode o ? o.deepCopy() : Json.obj();
    picks.forEach((kind, pair) -> {
      ObjectNode byWording = value.path(kind).isObject() ? (ObjectNode) value.get(kind)
          : value.putObject(kind);
      if (byWording.size() >= 50 && !byWording.has(ItemMatch.fold(pair[0]))) return;
      byWording.put(ItemMatch.fold(pair[0]), pair[1]);
    });
    row.setValue(value);
    row.setUpdatedAt(java.time.Instant.now());
    repo.save(row);
    state.remove(List.of("wanted", "wantedWithin"));
  }

  /**
   * Reads the list the step chooses from as the page (or app) shows it now. The listing is a read
   * on the same page and device; its result wakes the step, which then decides or asks.
   */
  private Outcome listChoices(Execution execution, DeviceCommands broker, ObjectNode state,
                              DeviceTarget target, UUID workspaceId, String listAction,
                              ObjectNode listInput, String into) {
    if (state.hasNonNull("listCommandId")) {
      DeviceCommandDto listing = broker.find(UUID.fromString(state.get("listCommandId").asText()))
          .orElse(null);
      if (listing == null) {
        state.remove("listCommandId");
        return Outcome.retryable("The list read from the page is gone");
      }
      if (!listing.status().finished()) return Outcome.awaitDevice(listing.deadlineAt());
      state.remove("listCommandId");
      boolean throughFirstCard = "choices".equals(into)
          && state.path("parentUnmatched").asBoolean(false);
      if (throughFirstCard && (listing.status()
          != com.jizza.engine.api.device.DeviceCommandStatus.SUCCEEDED
          || ItemMatch.items(listing.output().path("items")).isEmpty())) {
        // The items could not be read through the first card: ask for the card on its own.
        state.remove(List.of("parentUnmatched", "parent", "choices", "groups"));
        return execute(execution);
      }
      if (listing.status() != com.jizza.engine.api.device.DeviceCommandStatus.SUCCEEDED) {
        preserveError(state, listing);
        return Outcome.retryable("Could not read the list on the page: "
            + (listing.error() == null ? listing.status().name().toLowerCase() : listing.error()));
      }
      ArrayNode items = state.putArray(into);
      java.util.Map<Integer, String> shots = new java.util.HashMap<>();
      for (JsonNode raw : listing.output().path("items")) {
        if (raw.path("shot").isTextual() && !raw.path("shot").asText().isBlank()) {
          shots.put(raw.path("index").asInt(-1), raw.get("shot").asText());
        }
      }
      if (!shots.isEmpty() && listing.deviceId() != null) {
        state.put("shotsDevice", listing.deviceId().toString());
      }
      ItemMatch.items(listing.output().path("items")).stream().limit(200)
          .forEach(i -> {
            ObjectNode item = items.addObject().put("label", i.label()).put("text", i.text())
                .put("index", i.index());
            String shot = shots.get(i.index());
            if (shot != null) item.put("shot", shot);
          });
      if (items.isEmpty()) {
        state.remove(into);
        return Outcome.retryable("The list on the page has no items to choose from");
      }
      JsonNode groups = listing.output().path("groups");
      if ("choices".equals(into) && groups.isArray() && !groups.isEmpty()) {
        ArrayNode kept = state.putArray("groups");
        for (JsonNode group : groups) {
          if (kept.size() >= 40) break;
          ObjectNode g = kept.addObject().put("index", group.path("index").asInt(-1))
              .put("label", group.path("label").asText(""));
          ArrayNode list = g.putArray("items");
          for (JsonNode item : group.path("items")) {
            if (list.size() >= 100) break;
            list.addObject().put("label", item.path("label").asText(""))
                .put("text", item.path("text").asText("")).put("index", item.path("index").asInt());
          }
        }
      } else if ("choices".equals(into)) {
        state.remove("groups");
      }
      return execute(execution);
    }
    DeviceCommandDto listing;
    try {
      listing = broker.submit(new DeviceCommandRequest(execution.userId(), workspaceId, target,
          listAction, 1, listInput, null, execution.runId(), execution.node().id(),
          execution.runId() + ":" + execution.node().id() + ":" + execution.attempt() + ":list"
              + state.path("lists").asInt(0),
          false));
      state.put("lists", state.path("lists").asInt(0) + 1);
    } catch (ValidationException | NotFoundException e) {
      return Outcome.permanentFailure(e.getMessage());
    }
    state.put("listCommandId", listing.id().toString());
    if (listing.status().finished()) {
      return listChoices(execution, broker, state, target, workspaceId, listAction, listInput, into);
    }
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
      case SUCCEEDED -> {
        JsonNode errors = command.output() == null ? null : command.output().path("errors");
        if (WebStepForm.FILL_FORMS.contains(command.action()) && errors != null
            && errors.isArray() && !errors.isEmpty() && state.path("errorRounds").asInt(0) < 3) {
          // The site refused what was typed; the person fixes it and the form is sent again.
          state.remove("commandId");
          state.set("siteErrors", errors.deepCopy());
          state.remove("siteErrorsAsked");
          state.put("errorRounds", state.path("errorRounds").asInt(0) + 1);
          yield execute(execution);
        }
        JsonNode output = outputOf(command,
            state.path("browserAction").asBoolean(false),
            state.path("browserOpened").asBoolean(true),
            state.path("browserWorkspace").asText(null));
        // The person's answers to the step's details ride along as its `details` output.
        if (state.path("details").isObject() && output instanceof ObjectNode object) {
          object.set("details", state.get("details"));
        }
        if (execution.node().config() != null) {
          ReadAs.apply(execution.node().config().path("readAs"), output);
        }
        String stop = stopsOn(execution.node().config(), output);
        if (stop != null) yield Outcome.permanentFailure(stop);
        yield Outcome.completed(output);
      }
      case FAILED -> {
        // The next attempt sends a new command rather than reading this one again.
        state.remove("commandId");
        preserveError(state, command);
        String kind = command.output() == null ? null
            : command.output().path("error").path("kind").asText(null);
        String message = failureMessage(command);
        JsonNode stepConfig = execution.node().config();
        if (stepConfig != null && stepConfig.path("optional").asBoolean(false)
            && kind != null && SKIPPABLE_WHEN_OPTIONAL.contains(kind)) {
          // An optional step whose element is not on the page this time (a pop-up some pages
          // show): the run goes on without it.
          yield Outcome.completed(Json.obj().put("skipped", true).put("reason", message));
        }
        JsonNode listedNow = command.output() == null ? null
            : command.output().path("error").path("items");
        if ("item_not_found".equals(kind) && WebStepForm.pickKey(command.action()) != null
            && listedNow != null && listedNow.isArray() && !listedNow.isEmpty()
            && state.path("notFound").asInt(0) < 2) {
          // What was asked for is not there: choose from what is, as a listed choice is made.
          state.put("notFound", state.path("notFound").asInt(0) + 1);
          // The card to choose inside is the one gone: choose that again first.
          boolean within = command.output().path("error").path("within").asBoolean(false);
          ArrayNode items = state.putArray(within ? "parents" : "choices");
          if (within) state.remove(List.of("parent", "choices"));
          ItemMatch.items(listedNow).stream().limit(200)
              .forEach(i -> items.addObject().put("label", i.label()).put("text", i.text())
              .put("index", i.index()));
          yield execute(execution);
        }
        if (irreversible(stepConfig)) {
          // Paying, booking, placing an order: never sent again by itself, as it may have gone
          // through. The person checks the site and says so before it is tried again.
          yield Outcome.permanentFailure(message);
        }
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
        yield irreversible(execution.node().config())
            ? Outcome.permanentFailure(failureMessage(command)
                + ". The browser did not say whether it went through")
            : Outcome.retryable(failureMessage(command));
      }
      default -> Outcome.permanentFailure(command.explain());
    };
  }

  static final String UPLOAD_FILE = "browser.uploadFile";

  private ObjectProvider<com.jizza.engine.api.device.Devices> devices;
  private ObjectProvider<com.jizza.engine.api.chat.ChatFiles> chatFiles;

  /** Where a file to upload comes from: a file a device sent back, or one added in chat. */
  @org.springframework.beans.factory.annotation.Autowired
  public void setFileSources(ObjectProvider<com.jizza.engine.api.device.Devices> devices,
                             ObjectProvider<com.jizza.engine.api.chat.ChatFiles> chatFiles) {
    this.devices = devices;
    this.chatFiles = chatFiles;
  }

  /**
   * "Upload a file": the step names one of the person's files by id (a chat attachment, a run
   * input, a file an earlier step downloaded). The browser can only fetch a file handed to it, so
   * the file is handed to the browser that runs the step, once per step, and the input becomes
   * {@code {id, name, contentType, size}}. Null when ready, else why it can't be.
   */
  private String handOver(Execution execution, ObjectNode inputs, DeviceTarget target,
                          ObjectNode state) {
    JsonNode given = inputs.get("file");
    String ref = given == null ? "" : given.isObject() ? given.path("id").asText("") : given.asText("");
    UUID source;
    try {
      source = UUID.fromString(ref.strip());
    } catch (IllegalArgumentException e) {
      return "Say which file to upload";
    }
    JsonNode handed = state.path("handedFile");
    if (handed.isObject() && ref.strip().equals(handed.path("from").asText())
        && target.deviceId() != null && target.deviceId().toString().equals(handed.path("to").asText())) {
      inputs.set("file", handed.path("file").deepCopy());
      return null;
    }
    if (target.deviceId() == null) {
      return "Open the page in a browser first: the file is handed to the browser that shows it";
    }
    com.jizza.engine.api.device.Devices registry = devices == null ? null : devices.getIfAvailable();
    if (registry == null) return "Files can't be handed to browsers here";
    String name;
    String contentType;
    byte[] bytes;
    var fromDevice = registry.file(source).filter(f -> registry.owned(execution.userId()).stream()
        .anyMatch(d -> d.id().equals(f.deviceId())));
    if (fromDevice.isPresent()) {
      name = fromDevice.get().name();
      contentType = fromDevice.get().contentType();
      bytes = fromDevice.get().content();
    } else {
      com.jizza.engine.api.chat.ChatFiles chat = chatFiles == null ? null : chatFiles.getIfAvailable();
      var fromChat = chat == null ? java.util.Optional.<com.jizza.engine.api.chat.ChatFiles.Content>empty()
          : chat.content(execution.userId(), source);
      if (fromChat.isEmpty()) return "The file to upload is not one of yours, or it is gone";
      name = fromChat.get().name();
      contentType = fromChat.get().contentType();
      bytes = fromChat.get().bytes();
    }
    UUID id;
    try {
      id = registry.handFile(target.deviceId(), name, contentType, bytes);
    } catch (RuntimeException e) {
      return "The file could not be handed to the browser: " + e.getMessage();
    }
    ObjectNode file = Json.obj().put("id", id.toString()).put("name", name == null ? "file" : name)
        .put("contentType", contentType == null ? "application/octet-stream" : contentType)
        .put("size", bytes.length);
    inputs.set("file", file);
    state.putObject("handedFile").put("from", ref.strip()).put("to", target.deviceId().toString())
        .set("file", file.deepCopy());
    return null;
  }

  /** A step marked as one that cannot be undone, such as paying or placing an order. */
  static boolean irreversible(JsonNode config) {
    return config != null && config.path("irreversible").asBoolean(false);
  }

  /**
   * "Wait for what happens": the outcome the page showed, when the step says the run stops there
   * ("No trains found", "Seats not available"); the message the run stops with, else null.
   */
  static String stopsOn(JsonNode config, JsonNode output) {
    if (config == null || output == null || !output.path("outcome").isTextual()) return null;
    String outcome = output.get("outcome").asText();
    JsonNode rule = config.path("onOutcome").path(outcome);
    if (!"stop".equals(rule.path("then").asText(""))) return null;
    String message = rule.path("message").asText("").strip();
    return message.isEmpty() ? "The page showed \"" + outcome + "\"" : message;
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
