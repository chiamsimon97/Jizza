package com.jizza.engine.webauto;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.authz.Access;
import com.jizza.engine.api.autonomy.AutonomyGate;
import com.jizza.engine.api.device.DeviceActionCatalog;
import com.jizza.engine.api.device.DeviceActionSpec;
import com.jizza.engine.api.device.DeviceCommandDto;
import com.jizza.engine.api.device.DeviceCommandRequest;
import com.jizza.engine.api.device.DeviceCommandStatus;
import com.jizza.engine.api.device.DeviceCommands;
import com.jizza.engine.api.device.DeviceDto;
import com.jizza.engine.api.device.DeviceTarget;
import com.jizza.engine.api.device.Devices;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.orchestrator.AgentDirectory;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.webauto.DesignTimeService;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Tests one browser step literally, so a template or selector is never interpreted differently
 * from what the person sees while configuring it.
 */
@Service
public class DefaultDesignTimeService implements DesignTimeService {

  private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(60);
  private static final Duration WAIT = Duration.ofSeconds(55);
  private static final Duration PICK_TIMEOUT = Duration.ofSeconds(150);
  private static final String PICK_ACTION = "browser.pickElement";

  private final Devices devices;
  private final DeviceCommands commands;
  private final DeviceActionCatalog catalog;
  private final AgentDirectory agents;
  private final AutonomyGate autonomy;

  public DefaultDesignTimeService(Devices devices, DeviceCommands commands,
                                  DeviceActionCatalog catalog, AgentDirectory agents,
                                  AutonomyGate autonomy) {
    this.devices = devices;
    this.commands = commands;
    this.catalog = catalog;
    this.agents = agents;
    this.autonomy = autonomy;
  }

  @Override
  public Result testStep(Access access, UUID deviceId, String action, Integer version,
                         JsonNode inputs) {
    DeviceDto device = devices.list(access.workspaceId(), access.userId()).stream()
        .filter(candidate -> candidate.id().equals(deviceId))
        .findFirst()
        .orElseThrow(() -> new NotFoundException("Browser", deviceId));
    if (action == null || !action.startsWith("browser.")) {
      throw new ValidationException("Choose a web automation step");
    }
    DeviceActionSpec spec = catalog.resolve(action, version == null ? 0 : version)
        .filter(candidate -> "browser".equals(candidate.capability()))
        .orElseThrow(() -> new ValidationException("That web automation step is not available"));
    if (!device.declares(spec.action())) {
      throw new ValidationException(device.name() + " does not offer " + spec.title());
    }
    JsonNode literalInputs = inputs == null || inputs.isNull() ? Json.obj() : inputs;
    if (!literalInputs.isObject()) throw new ValidationException("Step inputs must be an object");

    judge(access, device, spec, literalInputs);

    DeviceCommandDto sent = commands.submit(new DeviceCommandRequest(access.userId(),
        access.workspaceId(), DeviceTarget.device(device.id()), spec.action(), spec.version(),
        literalInputs, COMMAND_TIMEOUT, null, null, "web-test:" + UUID.randomUUID(), true));
    return result(commands.await(sent.id(), WAIT));
  }

  @Override
  public PickStarted startPick(Access access, UUID deviceId, String sessionId, String url,
                               String prompt) {
    DeviceDto device = devices.list(access.workspaceId(), access.userId()).stream()
        .filter(candidate -> candidate.id().equals(deviceId))
        .findFirst()
        .orElseThrow(() -> new NotFoundException("Browser", deviceId));
    DeviceActionSpec spec = catalog.resolve(PICK_ACTION, 0)
        .filter(candidate -> "browser".equals(candidate.capability()))
        .orElseThrow(() -> new ValidationException("Picking is not available"));
    if (!device.declares(spec.action())) {
      throw new ValidationException(device.name() + " cannot pick elements");
    }
    var input = Json.obj();
    if (sessionId != null && !sessionId.isBlank()) input.put("session", sessionId);
    if (url != null && !url.isBlank()) input.put("url", url);
    if (prompt != null && !prompt.isBlank()) input.put("prompt", prompt);

    judge(access, device, spec, input);

    DeviceCommandDto sent = commands.submit(new DeviceCommandRequest(access.userId(),
        access.workspaceId(), DeviceTarget.device(device.id()), spec.action(), spec.version(),
        input, PICK_TIMEOUT, null, null, "web-pick:" + UUID.randomUUID(), true));
    return new PickStarted(sent.id());
  }

  @Override
  public PickResult pickResult(Access access, UUID commandId) {
    DeviceCommandDto command = commands.find(commandId)
        .filter(c -> PICK_ACTION.equals(c.action()) && access.userId().equals(c.userId()))
        .orElseThrow(() -> new NotFoundException("Pick", commandId));
    if (!command.status().finished()) return new PickResult("waiting", null, null, null, null, null);
    JsonNode output = command.output();
    if (command.status() == DeviceCommandStatus.SUCCEEDED && output != null
        && output.path("element").isObject()) {
      return new PickResult("done", output.get("element"), output.path("preview").asText(null),
          output.path("pagePreview").asText(null), output.path("url").asText(null), null);
    }
    Result failed = result(command);
    Error error = failed.error() != null && failed.error().kind() != null ? failed.error()
        : new Error(command.status() == DeviceCommandStatus.CANCELLED ? "cancelled_by_person"
            : "failed", command.error(), null);
    return new PickResult("failed", null, null, null, null, error);
  }

  private void judge(Access access, DeviceDto device, DeviceActionSpec spec, JsonNode inputs) {
    new WebGuardrails(agents, autonomy).judge(access, device, spec, inputs, "This test");
  }

  private Result result(DeviceCommandDto command) {
    String status = switch (command.status()) {
      case SUCCEEDED -> "succeeded";
      case EXPIRED -> "timed_out";
      case FAILED, REJECTED, CANCELLED -> "failed";
      default -> "timed_out";
    };
    JsonNode errorNode = command.output() == null ? null : command.output().path("error");
    Error error = errorNode != null && errorNode.isObject()
        ? new Error(errorNode.path("kind").asText(command.errorCode()),
            errorNode.path("detail").asText(command.error()), errorNode.get("candidates"))
        : command.status() == DeviceCommandStatus.SUCCEEDED ? null
            : new Error(command.errorCode(), command.error(), null);
    return new Result(status, command.output(), error,
        command.files() == null ? List.of() : command.files(), command.error());
  }
}
