package com.jizza.engine.chat.steps;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.authz.Access;
import com.jizza.engine.api.chat.ChatScope;
import com.jizza.engine.api.error.JizzaException;
import com.jizza.engine.api.orchestrator.UiAction;
import com.jizza.engine.api.tool.JsonSchemas;
import com.jizza.engine.api.tool.ToolCall;
import com.jizza.engine.api.tool.ToolProvider;
import com.jizza.engine.api.tool.ToolResult;
import com.jizza.engine.api.tool.ToolSpec;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.webauto.Recordings;
import com.jizza.engine.persistence.entity.AgentEntity;
import com.jizza.engine.persistence.entity.DeviceEntity;
import com.jizza.engine.persistence.repository.AgentRepository;
import com.jizza.engine.persistence.repository.DeviceRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * {@code web_record}: starts recording a web task in the person's own browser, and stops it or
 * opens its review when they press the buttons on the card.
 *
 * <p>Recording only watches what the person does themselves, so the guardrails treat it as a read.
 * The captured steps are reviewed on the Recordings page and saved from there as a module; nothing
 * is saved, and nothing runs, from chat. Offered only to Jizza answering the person.
 */
@Component
public class WebRecordToolProvider implements ToolProvider {

  public static final String WEB_RECORD = "web_record";

  private static final String RECORD_ACTION = "browser.record";

  private final AgentRepository agents;
  private final DeviceRepository devices;
  private final ObjectProvider<Recordings> recordings;
  private final ToolSpec spec;

  public WebRecordToolProvider(AgentRepository agents, DeviceRepository devices,
                               ObjectProvider<Recordings> recordings) {
    this.agents = agents;
    this.devices = devices;
    this.recordings = recordings;
    this.spec = new ToolSpec(WEB_RECORD,
        "Start recording a task the user will do in their own Chrome, so it can be saved as a "
            + "reusable module. Use it when the user wants to show you a web task once. Give the "
            + "page to start on and a name. It puts a card on the screen with Stop and Open "
            + "review buttons; after calling it, say in a sentence that they should do the task "
            + "and press Stop, and end your turn. When they press Stop their reply asks you to "
            + "call this again with action \"stop\" and the recording id; for the Open review "
            + "button use action \"review\". Never click around in their browser to do this "
            + "yourself.",
        JsonSchemas.object(new LinkedHashMap<>(Map.of(
            "action", JsonSchemas.string("start (default), stop or review"),
            "deviceId", JsonSchemas.string("Optional: the browser agent to record in; needed "
                + "only when the user has more than one"),
            "url", JsonSchemas.string("start: the https page to begin on"),
            "name", JsonSchemas.string("start: a short name for the task, e.g. \"Download "
                + "invoices\""),
            "recordingId", JsonSchemas.string("stop and review: the id start returned"))),
            List.of()));
  }

  @Override
  public List<ToolSpec> toolsFor(UUID agentId) {
    return available(agentId) ? List.of(spec) : List.of();
  }

  @Override
  public boolean supports(UUID agentId, String toolName) {
    return WEB_RECORD.equals(toolName) && available(agentId);
  }

  @Override
  public ToolResult invoke(UUID agentId, ToolCall call) {
    Recordings service = recordings.getIfAvailable();
    AgentEntity agent = agents.findById(agentId).filter(AgentEntity::isOrchestrator).orElse(null);
    if (service == null || agent == null) return ToolResult.error("Recording is not available");
    JsonNode args = call.args() == null ? Json.obj() : call.args();
    String action = args.path("action").asText("start").strip().toLowerCase();
    try {
      return switch (action) {
        case "start", "" -> start(service, agent, args);
        case "stop" -> stop(service, agent, args);
        case "review" -> review(service, agent, args);
        default -> ToolResult.error("'action' must be start, stop or review");
      };
    } catch (JizzaException e) {
      return ToolResult.error(e.getMessage());
    }
  }

  private ToolResult start(Recordings service, AgentEntity agent, JsonNode args) {
    String url = args.path("url").asText("").strip();
    String name = args.path("name").asText("").strip();
    if (url.isEmpty() || name.isEmpty()) return ToolResult.error("Give 'url' and 'name' to start");
    List<DeviceEntity> browsers = devices
        .findByOwnerUserIdAndStatusOrderByCreatedAtAsc(agent.getUserId(), "active").stream()
        .filter(d -> "browser".equals(d.getPlatform()) && declaresRecord(d)).toList();
    String wanted = args.path("deviceId").asText("").strip();
    DeviceEntity device;
    if (!wanted.isEmpty()) {
      device = browsers.stream().filter(d -> d.getId().toString().equals(wanted)).findFirst()
          .orElse(null);
      if (device == null) return ToolResult.error("That browser agent is not one of yours");
    } else if (browsers.size() == 1) {
      device = browsers.get(0);
    } else if (browsers.isEmpty()) {
      return ToolResult.error("The user has no browser agent that can record. Tell them to add "
          + "one under Settings > Agents > Create agent > Browser.");
    } else {
      return ToolResult.error("The user has several browser agents; ask which one, then pass "
          + "its deviceId: " + browsers.stream()
              .map(d -> d.getName() + " = " + d.getId()).toList());
    }
    Recordings.Recording started = service.start(
        new Access(agent.getUserId(), device.getWorkspaceId()), device.getId(), url, name);

    ObjectNode payload = Json.obj().put("title", "Recording in Chrome on " + device.getName())
        .put("source", "agent");
    payload.put("intro", "Do the task, then press Stop.");
    var steps = payload.putArray("steps");
    steps.addObject().put("label", "Do the task in Chrome")
        .put("detail", "Jizza records the clicks and typing; passwords are never kept.");
    steps.addObject().put("label", "Stop when you are done").putObject("button")
        .put("kind", "reply").put("label", "Stop")
        .put("text", "I'm done. Please stop the recording " + started.id() + ".");
    steps.addObject().put("label", "Check the captured steps").putObject("button")
        .put("kind", "reply").put("label", "Open review")
        .put("text", "Open the review for recording " + started.id() + ".");
    ChatScope.record(new UiAction(UiAction.SHOW_STEPS, payload));
    return ToolResult.ok(Json.obj().put("recordingId", started.id().toString())
        .put("note", "Recording has started in Chrome on " + device.getName() + ". The card with "
            + "Stop and Open review is on the user's screen. Say in a sentence that they should "
            + "do the task and press Stop, and end your turn."));
  }

  private ToolResult stop(Recordings service, AgentEntity agent, JsonNode args) {
    UUID id = idOf(args);
    if (id == null) return ToolResult.error("'recordingId' is not a recording id");
    Recordings.Recording stopped = service.stop(access(agent), id);
    return ToolResult.ok(Json.obj().put("status", stopped.status())
        .put("steps", stopped.stepCount())
        .put("note", "Recording stopped with " + stopped.stepCount() + " steps. Offer to open "
            + "the review, where they can rename steps, turn typed values into inputs and save "
            + "it as a module."));
  }

  private ToolResult review(Recordings service, AgentEntity agent, JsonNode args) {
    UUID id = idOf(args);
    if (id == null) return ToolResult.error("'recordingId' is not a recording id");
    Recordings.Recording found = service.get(access(agent), id);
    ChatScope.record(new UiAction(UiAction.NAVIGATE, Json.obj().put("page", "recordings")
        .put("reason", "Review \"" + found.name() + "\" and save it as a module")));
    return ToolResult.ok(Json.obj().put("status", found.status())
        .put("note", "The Recordings page is open. Tell them to resume the draft there to review "
            + "and save it."));
  }

  private static Access access(AgentEntity agent) {
    return new Access(agent.getUserId(), null);
  }

  private static UUID idOf(JsonNode args) {
    try {
      return UUID.fromString(args.path("recordingId").asText(""));
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private static boolean declaresRecord(DeviceEntity device) {
    if (device.getActions() == null) return false;
    for (JsonNode ref : device.getActions()) {
      if (ref.asText().split("@")[0].equals(RECORD_ACTION)) return true;
    }
    return false;
  }

  private boolean available(UUID agentId) {
    return recordings.getIfAvailable() != null && ChatScope.isResponder(agentId)
        && agents.findById(agentId).filter(AgentEntity::isOrchestrator).isPresent();
  }
}
