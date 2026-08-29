package com.jizza.procedures.learning;

import com.jizza.common.agent.Agent;
import com.jizza.common.agent.AgentRequest;
import com.jizza.common.enums.ModelTier;
import com.jizza.common.event.RepetitiveTaskCompleted;
import com.jizza.common.util.Json;
import com.jizza.persistence.repository.WorkflowRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Turns a completed repetitive job into a proposal (R34).
 *
 * <p>Deliberately conservative, per the Charter: the user marks a job repetitive, and only then
 * does the agent get asked to write down what it did. It runs once per job -- once a proposal
 * exists for the task, the agent is not asked again -- and it submits a draft for review rather
 * than changing anything.
 */
@Component
public class ProcedureLearner {

  private static final Logger log = LoggerFactory.getLogger(ProcedureLearner.class);

  private static final String INSTRUCTION = """
      You have just finished a job the user marked as repetitive:

      Task: %s
      What happened: %s

      Write down how this job is done, as a workflow, and submit it with propose_workflow so the
      user can review it. Capture the actual steps you take, in order, using connector tools you
      already hold and agent steps for judgement. Where a step needs a decision only the user can
      make, use a human_intervention node rather than guessing.

      Set source_task_id to %s. Submit exactly one proposal, then stop: it does not take effect
      until the user approves it.
      """;

  private final Agent baseAgent;
  private final WorkflowRepository workflows;

  public ProcedureLearner(Agent baseAgent, WorkflowRepository workflows) {
    this.baseAgent = baseAgent;
    this.workflows = workflows;
  }

  @EventListener
  @Async
  public void onRepetitiveTaskCompleted(RepetitiveTaskCompleted event) {
    if (!workflows.findBySourceTaskId(event.taskId()).isEmpty()) {
      // A proposal already exists for this job; revising it is the user's call, not a background one.
      return;
    }
    try {
      baseAgent.run(new AgentRequest(event.userId(), event.agentId(),
          INSTRUCTION.formatted(event.title(), event.outcome(), event.taskId()),
          Json.obj().put("taskId", event.taskId().toString()), List.of(), ModelTier.DEEP,
          true, null));
    } catch (RuntimeException e) {
      // Learning is best-effort: never let it turn a successful job into a failure.
      log.info("Could not capture a procedure for '{}': {}", event.title(), e.getMessage());
    }
  }
}
