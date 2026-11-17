package com.jizza.engine.workflow.nodes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.device.DeviceCommandDto;
import com.jizza.engine.api.device.DeviceCommandRequest;
import com.jizza.engine.api.device.DeviceCommands;
import com.jizza.engine.api.device.DeviceTarget;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.workflow.engine.NodeExecutor.Execution;
import com.jizza.engine.workflow.engine.NodeExecutor.Outcome;
import java.util.UUID;

/**
 * "Tap on a picture" (plan 06, seat maps): the person is shown a picture of a page area, such as a
 * cinema's seat map, taps the places they want, and the browser clicks the same places. The taps
 * are fractions of the area's width and height, so they land right whatever the screen size.
 */
final class TapPicture {

  private TapPicture() {}

  static final int MAX_TAPS = 20;

  /**
   * The taps in a step's inputs as {@code [{x, y}]}: given as objects, or as "x,y" text from the
   * card. Anything outside the picture, or past 20 taps, is left out.
   */
  static ArrayNode points(JsonNode given) {
    ArrayNode out = Json.mapper().createArrayNode();
    if (given == null || !given.isArray()) return out;
    for (JsonNode raw : given) {
      if (out.size() >= MAX_TAPS) break;
      double x;
      double y;
      if (raw.isObject() && raw.path("x").isNumber() && raw.path("y").isNumber()) {
        x = raw.get("x").asDouble();
        y = raw.get("y").asDouble();
      } else if (raw.isTextual() && raw.asText().matches("\\s*[0-9.]+\\s*,\\s*[0-9.]+\\s*")) {
        String[] parts = raw.asText().split(",");
        try {
          x = Double.parseDouble(parts[0].strip());
          y = Double.parseDouble(parts[1].strip());
        } catch (NumberFormatException e) {
          continue;
        }
      } else {
        continue;
      }
      if (x < 0 || x > 1 || y < 0 || y > 1) continue;
      out.addObject().put("x", Math.round(x * 10_000) / 10_000.0).put("y", Math.round(y * 10_000) / 10_000.0);
    }
    return out;
  }

  /**
   * Takes the picture the person taps on: a screenshot of the step's element. Null once it is
   * kept in {@code state.picture} ({@code {device, file}}), else what the step waits for.
   */
  static Outcome take(Execution execution, DeviceCommands broker, ObjectNode state,
                      DeviceTarget target, UUID workspaceId, ObjectNode inputs) {
    if (state.hasNonNull("pictureCommandId")) {
      DeviceCommandDto shot = broker.find(UUID.fromString(state.get("pictureCommandId").asText()))
          .orElse(null);
      if (shot == null) {
        state.remove("pictureCommandId");
      } else if (!shot.status().finished()) {
        return Outcome.awaitDevice(shot.deadlineAt());
      } else {
        state.remove("pictureCommandId");
        String file = shot.output() == null ? null : shot.output().path("file").asText(null);
        if (shot.status() != com.jizza.engine.api.device.DeviceCommandStatus.SUCCEEDED
            || file == null || shot.deviceId() == null) {
          return Outcome.retryable("Could not take a picture of the area to tap on: "
              + shot.explain());
        }
        state.putObject("picture").put("device", shot.deviceId().toString()).put("file", file);
        return null;
      }
    }
    if (!inputs.has("element")) return Outcome.permanentFailure("Point at the area to tap on");
    ObjectNode shotInput = Json.obj();
    if (inputs.hasNonNull("session")) shotInput.set("session", inputs.get("session"));
    shotInput.set("element", inputs.get("element").deepCopy());
    DeviceCommandDto shot;
    try {
      shot = broker.submit(new DeviceCommandRequest(execution.userId(), workspaceId, target,
          "browser.screenshot", 1, shotInput, null, execution.runId(), execution.node().id(),
          execution.runId() + ":" + execution.node().id() + ":" + execution.attempt()
              + ":picture" + state.path("pictures").asInt(0),
          false));
    } catch (ValidationException | NotFoundException e) {
      return Outcome.permanentFailure(e.getMessage());
    }
    state.put("pictures", state.path("pictures").asInt(0) + 1);
    state.put("pictureCommandId", shot.id().toString());
    return take(execution, broker, state, target, workspaceId, inputs);
  }
}
