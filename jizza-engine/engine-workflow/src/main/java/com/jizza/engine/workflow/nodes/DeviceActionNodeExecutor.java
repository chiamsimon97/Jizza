package com.jizza.engine.workflow.nodes;

import com.fasterxml.jackson.databind.JsonNode;
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
import com.jizza.engine.persistence.entity.AgentEntity;
import com.jizza.engine.persistence.repository.AgentRepository;
import com.jizza.engine.workflow.engine.NodeExecutor;
import com.jizza.engine.workflow.sandbox.JexlSandbox;
import com.jizza.engine.workflow.sandbox.Templates;
import java.time.Duration;
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

  public DeviceActionNodeExecutor(ObjectProvider<DeviceCommands> commands,
                                  ObjectProvider<DeviceActionCatalog> catalog,
                                  AutonomyGate autonomyGate, JexlSandbox sandbox,
                                  AgentRepository agents) {
    this.commands = commands;
    this.catalog = catalog;
    this.autonomyGate = autonomyGate;
    this.sandbox = sandbox;
    this.agents = agents;
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
    if (state.hasNonNull("commandId")) {
      return follow(broker, state, UUID.fromString(state.get("commandId").asText()));
    }

    JsonNode config = execution.node().config();
    if (config == null || !config.hasNonNull("action")) {
      return Outcome.permanentFailure("Choose what this step does on the device");
    }
    int version = config.path("version").asInt(0);
    DeviceTarget target;
    try {
      target = DeviceTarget.from(config.get("target"));
    } catch (IllegalArgumentException e) {
      return Outcome.permanentFailure("The device this step names is not a device id");
    }
    UUID workspaceId = null;
    if (config.hasNonNull("workspaceId")) {
      try {
        workspaceId = UUID.fromString(config.get("workspaceId").asText());
      } catch (IllegalArgumentException e) {
        return Outcome.permanentFailure("'workspaceId' is not an account id");
      }
    }
    String action = config.get("action").asText();
    // A tool a device added itself is described by that device, and typed as its owner said.
    Optional<DeviceActionSpec> found = action.startsWith(DeviceActionSpec.EXTENSION_PREFIX)
        ? broker.describe(new DeviceCommandRequest(execution.userId(), workspaceId, target, action,
            version, null, null, execution.runId(), execution.node().id(), null, false))
        : actions.resolve(action, version);
    if (found.isEmpty()) {
      return Outcome.permanentFailure("Devices have no action called '" + action + "'");
    }
    DeviceActionSpec spec = found.get();

    JsonNode inputs;
    try {
      inputs = render(config.path("inputs"), execution.context().variables());
    } catch (SandboxViolationException | IllegalArgumentException e) {
      return Outcome.permanentFailure("The step's inputs could not be prepared: " + e.getMessage());
    }
    if (!inputs.isObject()) return Outcome.permanentFailure("The step's inputs must be an object");

    UUID actor = execution.owningAgentId() != null ? execution.owningAgentId()
        : agents.findByUserIdAndIsOrchestratorTrue(execution.userId()).map(AgentEntity::getId)
            .orElse(null);
    if (actor == null) {
      return Outcome.permanentFailure("No agent is available to run " + spec.title()
          + " under your guardrails");
    }
    String toolRef = toolRef(target, spec);
    GuardrailContext guardrail = execution.workflowId() == null
        ? new GuardrailContext(actor, toolRef, inputs, null, null, null, false, spec.actionType())
        : GuardrailContext.inWorkflow(actor, toolRef, inputs, execution.workflowId(),
            execution.workflowVersion(), execution.node().id(), spec.actionType());
    boolean approved = execution.context().approved(execution.node().id());
    Decision decision = approved ? autonomyGate.evaluate(guardrail) : autonomyGate.decide(guardrail);
    if (decision.denied()) {
      return Outcome.permanentFailure("Blocked by your guardrails: " + decision.reason());
    }
    if (!approved && (decision.needsPermission() || decision.needsJizza())) {
      return Outcome.awaitPermission("This step needs your approval before it asks "
          + target.describe() + " to " + lower(spec.title()) + ". " + decision.reason() + ".");
    }

    Duration timeout = config.path("timeoutSec").asInt(0) > 0
        ? Duration.ofSeconds(config.get("timeoutSec").asInt()) : null;
    DeviceCommandDto command;
    try {
      command = broker.submit(new DeviceCommandRequest(execution.userId(), workspaceId, target,
          spec.action(), spec.version(), inputs, timeout, execution.runId(),
          execution.node().id(),
          execution.runId() + ":" + execution.node().id() + ":" + execution.attempt(), false));
    } catch (ValidationException | NotFoundException e) {
      return Outcome.permanentFailure(e.getMessage());
    }
    state.put("commandId", command.id().toString());
    state.put("device", command.deviceName());
    return follow(broker, state, command.id());
  }

  private Outcome follow(DeviceCommands broker, ObjectNode state, UUID commandId) {
    DeviceCommandDto command = broker.find(commandId).orElse(null);
    if (command == null) {
      state.remove("commandId");
      return Outcome.retryable("The command sent to the device is gone");
    }
    if (!command.status().finished()) return Outcome.awaitDevice(command.deadlineAt());
    return switch (command.status()) {
      case SUCCEEDED -> Outcome.completed(outputOf(command));
      case FAILED, EXPIRED -> {
        // The next attempt sends a new command rather than reading this one again.
        state.remove("commandId");
        yield Outcome.retryable(command.explain());
      }
      default -> Outcome.permanentFailure(command.explain());
    };
  }

  /** What the device returned, with which device did it and the files it sent back. */
  static JsonNode outputOf(DeviceCommandDto command) {
    ObjectNode out = command.output() != null && command.output().isObject()
        ? ((ObjectNode) command.output()).deepCopy() : Json.obj();
    if (command.output() != null && !command.output().isObject() && !command.output().isNull()) {
      out.set("value", command.output());
    }
    out.putObject("device").put("id", command.deviceId().toString())
        .put("name", command.deviceName());
    if (command.files() != null && !command.files().isEmpty()) {
      var files = out.putArray("files");
      command.files().forEach(f -> files.add(f.toString()));
    }
    return out;
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
  private JsonNode render(JsonNode value, Map<String, Object> variables) {
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
