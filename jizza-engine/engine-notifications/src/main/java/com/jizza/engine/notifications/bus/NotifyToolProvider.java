package com.jizza.engine.notifications.bus;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.enums.NotificationKind;
import com.jizza.engine.api.notification.NotificationBus;
import com.jizza.engine.api.notification.NotificationContext;
import com.jizza.engine.api.tool.JsonSchemas;
import com.jizza.engine.api.tool.ToolCall;
import com.jizza.engine.api.tool.ToolProvider;
import com.jizza.engine.api.tool.ToolResult;
import com.jizza.engine.api.tool.ToolSpec;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.persistence.entity.AgentEntity;
import com.jizza.engine.persistence.repository.AgentRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@code notify_user} and {@code request_permission} (R17/R37). Specialists get both; they are how
 * a specialist reaches the user at all, and everything they raise is relayed by Jizza under the
 * specialist's own name.
 */
@Component
public class NotifyToolProvider implements ToolProvider {

  public static final String NOTIFY_USER = "notify_user";
  public static final String REQUEST_PERMISSION = "request_permission";

  private final NotificationBus bus;
  private final AgentRepository agents;
  private final Map<String, ToolSpec> specs = new LinkedHashMap<>();

  public NotifyToolProvider(NotificationBus bus, AgentRepository agents) {
    this.bus = bus;
    this.agents = agents;

    specs.put(NOTIFY_USER, new ToolSpec(NOTIFY_USER,
        "Tell the user something. It reaches them through Jizza, attributed to you by name. Use "
            + "this for updates and findings, not for questions you need answered.",
        JsonSchemas.object(new LinkedHashMap<>(Map.of(
            "message", JsonSchemas.string("What to tell the user, in plain language"))),
            List.of("message"))));

    specs.put(REQUEST_PERMISSION, new ToolSpec(REQUEST_PERMISSION,
        "Ask the user to approve something before you do it. Your task stops here until they "
            + "answer; do not look for another way to achieve the same effect.",
        JsonSchemas.object(new LinkedHashMap<>(Map.of(
            "what", JsonSchemas.string("What you want to do, specifically"),
            "why", JsonSchemas.string("Why it is needed"))),
            List.of("what")),
        true));
  }

  @Override
  public List<ToolSpec> toolsFor(UUID agentId) {
    return isSpecialist(agentId) ? List.copyOf(specs.values()) : List.of();
  }

  @Override
  public boolean supports(UUID agentId, String toolName) {
    return specs.containsKey(toolName) && isSpecialist(agentId);
  }

  @Override
  public ToolResult invoke(UUID agentId, ToolCall call) {
    AgentEntity agent = agents.findById(agentId).orElse(null);
    if (agent == null) return ToolResult.error("Unknown agent");
    JsonNode args = call.args() == null ? Json.obj() : call.args();

    if (NOTIFY_USER.equals(call.name())) {
      var raised = bus.raise(NotificationKind.INFO, agentId,
          NotificationContext.info(agent.getUserId(), args.path("message").asText()));
      return ToolResult.ok(Json.obj().put("notification_id", raised.id().toString())
          .put("note", "Delivered to the user through Jizza."));
    }

    if (REQUEST_PERMISSION.equals(call.name())) {
      String what = args.path("what").asText();
      String why = args.path("why").asText(null);
      var raised = bus.raise(NotificationKind.PERMISSION_REQUEST, agentId,
          new NotificationContext(agent.getUserId(),
              agent.getName() + " needs your approval: " + what,
              Json.obj().put("what", what).put("why", why), null, null, null, null));
      return ToolResult.ok(Json.obj().put("notification_id", raised.id().toString())
          .put("status", "waiting_for_approval")
          .put("note", "Stop here. Nothing happens until the user answers."));
    }
    return ToolResult.error("Unknown notification tool " + call.name());
  }

  private boolean isSpecialist(UUID agentId) {
    return agents.findById(agentId).map(a -> !a.isOrchestrator()).orElse(false);
  }
}
