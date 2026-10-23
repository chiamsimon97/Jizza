package com.jizza.platform.usecase.procedure;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.dto.AgentDto;
import com.jizza.engine.api.dto.TaskDto;
import com.jizza.engine.api.dto.WorkflowDto;
import com.jizza.engine.api.enums.NodeType;
import com.jizza.engine.api.enums.ScheduleType;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.orchestrator.AgentDirectory;
import com.jizza.engine.api.procedure.ProcedureService;
import com.jizza.engine.api.scheduler.SchedulerService;
import com.jizza.engine.api.scheduler.TaskSpec;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.workflow.graph.WorkflowEdge;
import com.jizza.engine.api.workflow.graph.WorkflowGraph;
import com.jizza.engine.api.workflow.graph.WorkflowNode;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Puts one worked example of the task-to-procedure link into a user's account: a weekly task
 * marked repetitive, and the procedure its agent "wrote down" from it, waiting for review.
 *
 * <p>It goes through the same {@link SchedulerService#schedule} and {@link ProcedureService#propose}
 * as the real thing, so the user sees exactly what they would after a repeating task's first run:
 * a review notification, the procedure under "Needs your review", and the task saying so. Every
 * step is built in, so approving it runs nothing outside Jizza.
 */
@Service
public class ProcedureExampleUseCase {

  static final String TASK_TITLE = "Example: Weekly check-in";
  static final String PROCEDURE_NAME = "Example: Weekly check-in";

  private final AgentDirectory agents;
  private final SchedulerService scheduler;
  private final ProcedureService procedures;

  public ProcedureExampleUseCase(AgentDirectory agents, SchedulerService scheduler,
                                 ProcedureService procedures) {
    this.agents = agents;
    this.scheduler = scheduler;
    this.procedures = procedures;
  }

  public record Example(TaskDto task, WorkflowDto procedure) {}

  @Transactional
  public Example create(UUID userId) {
    AgentDto agent = agents.list(userId).stream()
        .filter(a -> !a.orchestrator() && a.enabled() && a.active())
        .min(Comparator.comparing(AgentDto::name))
        .orElseThrow(() -> new ValidationException(
            "Add or turn on an agent first: an example task needs an agent to own it"));

    TaskDto task = scheduler.schedule(new TaskSpec(userId, agent.id(), TASK_TITLE,
        Json.obj().put("instruction", "Send me a short in-app note that the weekly example task "
            + "ran. Do not contact anyone or change anything else."),
        ScheduleType.RECURRING, "0 0 9 * * MON", null, null, true));

    WorkflowDto procedure = procedures.propose(agent.id(), task.id(), PROCEDURE_NAME,
        "Every Monday at 9am: counts the run, asks you whether to send the check-in, and if you "
            + "approve, sends you an in-app note. An example of the steps an agent writes down "
            + "from a repeating task.",
        graph());
    return new Example(task, procedure);
  }

  static JsonNode graph() {
    return new WorkflowGraph(
        List.of(
            new WorkflowNode("start", NodeType.TRIGGER, "Every Monday at 9am", null, null),
            new WorkflowNode("count", NodeType.STORAGE, "Count this week's run",
                Json.obj().put("action", "increment").put("key", "example_weekly_checkins"), null),
            new WorkflowNode("ask", NodeType.APPROVAL, "Ask me before sending",
                Json.obj().put("prompt", "Send this week's check-in note?"), null),
            new WorkflowNode("note", NodeType.NOTIFY, "Send me the check-in",
                Json.obj().put("title", "Weekly check-in")
                    .put("message", "Your example procedure ran: this is check-in number "
                        + "{{ nodes.count.value }}.")
                    .put("channel", "app"), null),
            new WorkflowNode("done", NodeType.END, "Done", null, null)),
        List.of(
            new WorkflowEdge("start", "count", null),
            new WorkflowEdge("count", "ask", null),
            new WorkflowEdge("ask", "note", "approved"),
            new WorkflowEdge("ask", "done", "rejected"),
            new WorkflowEdge("note", "done", null))).toJson();
  }
}
