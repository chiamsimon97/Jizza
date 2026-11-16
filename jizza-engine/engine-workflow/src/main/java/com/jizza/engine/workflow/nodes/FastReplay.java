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
import com.jizza.engine.api.device.DeviceCommandStatus;
import com.jizza.engine.api.device.DeviceCommands;
import com.jizza.engine.api.device.DeviceTarget;
import com.jizza.engine.api.enums.NodeType;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.workflow.graph.WorkflowEdge;
import com.jizza.engine.api.workflow.graph.WorkflowGraph;
import com.jizza.engine.api.workflow.graph.WorkflowNode;
import com.jizza.engine.workflow.engine.NodeExecutor.Execution;
import com.jizza.engine.workflow.engine.NodeExecutor.Outcome;
import com.jizza.engine.workflow.engine.RunContext;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fast replay: a module made only of web steps, in one straight line, is sent to the browser as
 * one {@code browser.runSteps} command instead of one command per step. It is an optimisation and
 * nothing more, so whenever it cannot be exactly what step-by-step would have done it steps aside:
 * a step needing approval, a step reading another step's output, a target other than the run's
 * browser, a browser without the action, or a batch that stops part-way. Every step still meets
 * its own guardrail before the batch is sent, and each node gets the output it would have got.
 *
 * <p>The first web step leads: it sends the batch and parks. When the batch finishes it keeps its
 * own output and leaves each later step's output in that step's state, where the step picks it up
 * when its turn comes. Steps after the one that failed run one by one, so a failure is reported,
 * retried and offered a fix exactly as it is without the batch.
 */
final class FastReplay {

  private static final Logger log = LoggerFactory.getLogger(FastReplay.class);

  static final String ACTION = "browser.runSteps";
  static final String BATCH_COMMAND = "batchCommandId";
  static final String BATCH_OUTPUT = "batchOutput";
  static final String TRIED = "batchTried";

  private static final int MAX_STEPS = 100;
  /** Choosing from a list reads the page first and may ask, so it never runs in a batch. */
  private static final Set<String> NOT_BATCHED = Set.of(ACTION, "browser.record", "browser.pickElement",
      "browser.listItems", WebStepForm.CLICK_ITEM, WebStepForm.CHOOSE_OPTION,
      WebStepForm.CLICK_ONE_OF, WebStepForm.SELECT_OPTION, "browser.listOptions",
      "browser.pickDate", "browser.ask", "browser.handOff");
  /** A template that reads another step's output can't be filled before that step has run. */
  private static final Pattern READS_STEPS = Pattern.compile("\\{\\{[^}]*\\bnodes\\b");

  private final boolean enabled;

  FastReplay(boolean enabled) {
    this.enabled = enabled;
  }

  boolean enabled() {
    return enabled;
  }

  /**
   * Sends the batch when this node leads an eligible module, or null to run it on its own. Either
   * way it is tried once per node.
   */
  Outcome start(Execution execution, DeviceCommands broker, DeviceActionCatalog catalog,
                AutonomyGate gate, UUID actor, BiFunction<JsonNode, Map<String, Object>, JsonNode> render) {
    ObjectNode state = execution.context().state(execution.node().id());
    if (!enabled || state.path(TRIED).asBoolean(false) || execution.attempt() > 1
        || execution.workflowId() == null || actor == null) {
      return null;
    }
    List<WorkflowNode> chain = chain(execution.graph());
    if (chain.size() < 2 || !chain.get(0).id().equals(execution.node().id())) return null;
    state.put(TRIED, true);

    RunContext context = execution.context();
    RunContext.Browser browser = context.browser();
    DeviceTarget target = browser != null ? DeviceTarget.device(browser.deviceId())
        : context.preferredBrowser() != null ? DeviceTarget.device(context.preferredBrowser())
        : new DeviceTarget(null, null, "browser", false);
    List<Planned> planned = new ArrayList<>();
    for (WorkflowNode node : chain) {
      JsonNode config = node.config();
      Optional<DeviceActionSpec> spec = catalog.resolve(config.path("action").asText(),
          config.path("version").asInt(0));
      if (spec.isEmpty() || !"browser".equals(spec.get().capability())
          || NOT_BATCHED.contains(spec.get().action())) {
        return null;
      }
      // A step that cannot be undone is never sent twice, and one whose outcome may stop the
      // run must be read by its own step: both run on their own.
      if (config.path("irreversible").asBoolean(false) || config.path("onOutcome").isObject()) {
        return null;
      }
      // A step with details attached stops for the person's answers, so it runs on its own.
      if (config.path("details").path("blocks").isArray()
          && !config.path("details").path("blocks").isEmpty()) {
        return null;
      }
      JsonNode inputs;
      try {
        inputs = render.apply(config.path("inputs"), context.variables());
      } catch (RuntimeException e) {
        return null;
      }
      if (!inputs.isObject()) return null;
      ObjectNode input = (ObjectNode) inputs;
      WebStepForm.normalize(spec.get().action(), input);
      // A form that is sent and checked may come back with the site's errors to fix.
      if (WebStepForm.FILL_FORM.equals(spec.get().action()) && input.has("submit")) return null;
      // A step with a mandatory value still empty runs on its own, where the person fills it in.
      if (WebStepForm.fields(spec.get(), config, input, null).stream()
          .anyMatch(WebStepForm.Field::missing)) {
        return null;
      }
      if ("browser.open".equals(spec.get().action()) && context.needsNewBrowserSession()
          && node == chain.get(0)) {
        input.put("in", "newSession");
      }
      planned.add(new Planned(node, spec.get(), input));
    }

    // Each step meets its own guardrail. Anything short of a plain allow runs step by step, where
    // the person is asked as usual.
    for (Planned p : planned) {
      Decision decision = gate.evaluate(guardrail(execution, actor, target, p));
      if (!decision.allowed()) return null;
    }
    for (Planned p : planned) {
      Decision decision = gate.decide(guardrail(execution, actor, target, p));
      if (!decision.allowed()) return null;
    }

    ObjectNode input = Json.obj();
    if (browser != null && !context.needsNewBrowserSession()) input.put("session", browser.sessionId());
    ArrayNode steps = input.putArray("steps");
    long timeoutSec = 0;
    for (Planned p : planned) {
      ObjectNode step = steps.addObject().put("key", p.node.id()).put("action", p.spec.action())
          .put("version", p.spec.version());
      step.set("input", p.input);
      int configured = p.node.config().path("timeoutSec").asInt(0);
      timeoutSec += configured > 0 ? configured : p.spec.defaultTimeoutSec();
    }
    input.put("stopOnError", true);

    DeviceCommandDto command;
    try {
      command = broker.submit(new DeviceCommandRequest(execution.userId(),
          browser == null ? null : browser.workspaceId(), target, ACTION, 1, input,
          Duration.ofSeconds(timeoutSec), execution.runId(), execution.node().id(),
          execution.runId() + ":" + execution.node().id() + ":batch", false));
    } catch (RuntimeException e) {
      // Most often the browser has no browser.runSteps yet: the steps go one by one.
      log.debug("Fast replay not used for run {}: {}", execution.runId(), e.getMessage());
      return null;
    }
    state.put(BATCH_COMMAND, command.id().toString());
    if (browser != null) {
      state.put("batchSession", browser.sessionId()).put("batchOpened", browser.opened());
      if (browser.workspaceId() != null) state.put("browserWorkspace", browser.workspaceId().toString());
    }
    return follow(execution, broker);
  }

  /**
   * The batch's result, once it has one: this node's own output, with later steps' outputs left
   * for them. Null when this node has to run on its own after all.
   */
  Outcome follow(Execution execution, DeviceCommands broker) {
    ObjectNode state = execution.context().state(execution.node().id());
    UUID commandId = UUID.fromString(state.get(BATCH_COMMAND).asText());
    DeviceCommandDto command = broker.find(commandId).orElse(null);
    if (command != null && !command.status().finished()) return Outcome.awaitDevice(command.deadlineAt());
    state.remove(BATCH_COMMAND);
    if (command == null) return null;
    // Stopped on purpose, as a single step stopped that way would be: not sent again.
    if (command.status() == DeviceCommandStatus.CANCELLED) return Outcome.permanentFailure(command.explain());

    JsonNode output = command.output() == null ? Json.obj() : command.output();
    JsonNode outputs = output.path("steps");
    String failedAt = output.path("failedAt").asText(null);
    boolean usable = command.status() == DeviceCommandStatus.SUCCEEDED
        || command.status() == DeviceCommandStatus.FAILED;
    String session = state.path("batchSession").asText(null);
    boolean opened = state.path("batchOpened").asBoolean(true);
    String workspace = state.path("browserWorkspace").asText(null);

    JsonNode own = null;
    for (JsonNode step : command.input().path("steps")) {
      String key = step.path("key").asText();
      if (!usable || key.equals(failedAt) || !outputs.has(key)) break;
      String action = step.path("action").asText();
      ObjectNode input = step.path("input").deepCopy();
      if (session != null && !input.has("session")) input.put("session", session);
      boolean newSession = "browser.open".equals(action) && "newSession".equals(input.path("in").asText());
      JsonNode mapped = DeviceActionNodeExecutor.stepOutput(outputs.get(key), command.deviceId(),
          command.deviceName(), null, action, input, true, newSession || session == null || opened,
          workspace);
      if ("browser.open".equals(action) && mapped.path("sessionId").isTextual()) {
        session = mapped.get("sessionId").asText();
        opened = true;
      }
      if (key.equals(execution.node().id())) {
        own = mapped;
      } else {
        execution.context().state(key).set(BATCH_OUTPUT, mapped);
      }
    }
    return own == null ? null : Outcome.completed(own);
  }

  /** The web steps of a module made only of them, in order, or empty when it is anything else. */
  static List<WorkflowNode> chain(WorkflowGraph graph) {
    if (graph == null) return List.of();
    List<WorkflowNode> out = new ArrayList<>();
    WorkflowNode trigger;
    try {
      trigger = graph.trigger();
    } catch (RuntimeException e) {
      return List.of();
    }
    int ends = 0;
    for (WorkflowNode node : graph.nodes()) {
      if (node.type() == NodeType.END) ends++;
      else if (node.type() != NodeType.TRIGGER && !web(node)) return List.of();
    }
    if (ends != 1) return List.of();
    WorkflowNode at = trigger;
    while (true) {
      List<WorkflowEdge> next = graph.outgoing(at.id());
      if (next.size() != 1 || next.get(0).branch() != null) return List.of();
      WorkflowNode to = graph.node(next.get(0).to()).orElse(null);
      if (to == null) return List.of();
      if (to.type() == NodeType.END) break;
      if (out.contains(to) || out.size() >= MAX_STEPS) return List.of();
      out.add(to);
      at = to;
    }
    return out.size() + 1 + ends == graph.nodes().size() ? out : List.of();
  }

  private static boolean web(WorkflowNode node) {
    JsonNode config = node.config();
    if (node.type() != NodeType.DEVICE_ACTION || config == null) return false;
    String target = config.path("target").asText("sameBrowser");
    return "web".equals(config.path("preset").asText())
        && config.path("action").asText("").startsWith("browser.")
        && (config.path("target").isMissingNode() || config.path("target").isNull()
            || "sameBrowser".equals(target))
        && !config.path("inputs").has("session")
        && !config.has("workspaceId")
        // Turned off or allowed to fail (recording edits): these run on their own.
        && !config.path("disabled").asBoolean(false)
        && !config.path("optional").asBoolean(false)
        && !READS_STEPS.matcher(Json.write(config.path("inputs"))).find();
  }

  private static GuardrailContext guardrail(Execution execution, UUID actor, DeviceTarget target,
                                            Planned p) {
    return GuardrailContext.inWorkflow(actor, DeviceActionNodeExecutor.toolRef(target, p.spec),
        p.input, execution.workflowId(), execution.workflowVersion(), p.node.id(),
        p.spec.actionType());
  }

  private record Planned(WorkflowNode node, DeviceActionSpec spec, ObjectNode input) {}
}
