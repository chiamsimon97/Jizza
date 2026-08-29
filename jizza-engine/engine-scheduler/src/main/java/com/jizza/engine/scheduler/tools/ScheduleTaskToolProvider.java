package com.jizza.engine.scheduler.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.enums.ScheduleType;
import com.jizza.engine.api.scheduler.SchedulerService;
import com.jizza.engine.api.scheduler.TaskSpec;
import com.jizza.engine.api.tool.JsonSchemas;
import com.jizza.engine.api.tool.ToolCall;
import com.jizza.engine.api.tool.ToolProvider;
import com.jizza.engine.api.tool.ToolResult;
import com.jizza.engine.api.tool.ToolSpec;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.persistence.entity.AgentEntity;
import com.jizza.engine.persistence.repository.AgentRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** {@code schedule_task}, available to every agent (R17). The owning agent is the caller. */
@Component
public class ScheduleTaskToolProvider implements ToolProvider {

  public static final String SCHEDULE_TASK = "schedule_task";

  private final SchedulerService scheduler;
  private final AgentRepository agents;
  private final ToolSpec spec;

  public ScheduleTaskToolProvider(SchedulerService scheduler, AgentRepository agents) {
    this.scheduler = scheduler;
    this.agents = agents;
    this.spec = new ToolSpec(SCHEDULE_TASK,
        "Schedule work to happen later, once or on a repeating schedule. It runs on the server "
            + "with no browser open, and you own it.",
        JsonSchemas.object(new LinkedHashMap<>(Map.of(
            "title", JsonSchemas.string("Short title for the task"),
            "instruction", JsonSchemas.string("What should be done when it runs"),
            "run_at", JsonSchemas.string(
                "ISO-8601 instant for a one-time task, e.g. 2026-03-01T09:00:00Z"),
            "schedule", JsonSchemas.string(
                "Cron expression or RRULE for a repeating task, e.g. '0 0 9 * * MON-FRI' or "
                    + "'FREQ=WEEKLY;BYDAY=MO;BYHOUR=9;BYMINUTE=0'"),
            "timezone", JsonSchemas.string("IANA zone, e.g. Asia/Kolkata"))),
            List.of("title", "instruction")),
        true);
  }

  @Override
  public List<ToolSpec> toolsFor(UUID agentId) { return List.of(spec); }

  @Override
  public boolean supports(UUID agentId, String toolName) { return SCHEDULE_TASK.equals(toolName); }

  @Override
  public ToolResult invoke(UUID agentId, ToolCall call) {
    AgentEntity agent = agents.findById(agentId).orElse(null);
    if (agent == null) return ToolResult.error("Unknown agent");

    JsonNode args = call.args() == null ? Json.obj() : call.args();
    String title = args.path("title").asText();
    String instruction = args.path("instruction").asText();
    String timezone = args.path("timezone").asText(null);
    JsonNode payload = Json.obj().put("instruction", instruction);

    boolean recurring = args.hasNonNull("schedule") && !args.path("schedule").asText().isBlank();
    if (!recurring && !args.hasNonNull("run_at")) {
      return ToolResult.error("Provide either 'run_at' for a one-time task or 'schedule' for a "
          + "repeating one");
    }

    try {
      TaskSpec taskSpec = recurring
          ? new TaskSpec(agent.getUserId(), agentId, title, payload, ScheduleType.RECURRING,
              args.path("schedule").asText(), null, timezone, false)
          : new TaskSpec(agent.getUserId(), agentId, title, payload, ScheduleType.ONE_TIME, null,
              Instant.parse(args.path("run_at").asText()), timezone, false);

      var task = scheduler.schedule(taskSpec);
      return ToolResult.ok(Json.obj()
          .put("task_id", task.id().toString())
          .put("title", task.title())
          .put("next_run_at", String.valueOf(task.nextRunAt())));
    } catch (java.time.format.DateTimeParseException e) {
      return ToolResult.error("'run_at' must be an ISO-8601 instant such as 2026-03-01T09:00:00Z");
    } catch (RuntimeException e) {
      return ToolResult.error(e.getMessage());
    }
  }
}
