package com.jizza.platform.usecase.procedure;

import com.jizza.engine.api.agent.Agent;
import com.jizza.engine.api.agent.AgentRequest;
import com.jizza.engine.api.enums.ModelTier;
import com.jizza.engine.api.event.RepetitiveTaskCompleted;
import com.jizza.engine.api.procedure.ProcedureService;
import com.jizza.engine.api.util.Json;
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
 *
 * <p>The prompt below is product copy about how Jizza asks its agents to work, not engine
 * mechanism, so it lives here rather than in {@code engine-procedures}; running the agent and
 * submitting the proposal it writes are ordinary uses of {@link Agent} and
 * {@link ProcedureService}, the same published capabilities any other caller has.
 */
@Component
public class ProcedureLearningUseCase {

  private static final Logger log = LoggerFactory.getLogger(ProcedureLearningUseCase.class);

  private static final String INSTRUCTION = """
      You have just finished a job the user marked as repetitive:

      Task: %s
      What happened: %s

      Write down how this job is done, as a workflow, and submit it with propose_workflow so the
      user can review it. Capture the actual steps you take, in order, so the job runs the same way
      every time: connector tools you already hold for actions, and built-in steps (FILTER, BRANCH,
      SWITCH, SET_VARIABLE, STORAGE, NOTIFY) for the logic in between. Use an AGENT_STEP only where
      a step truly needs judgement on free text. Put an APPROVAL step before anything the user
      would want to OK first, and a HUMAN_INTERVENTION step where only the user can decide.

      Give every step a short, plain label the user will understand (for example "Get this week's
      invoices"), and a one or two sentence description of what the whole workflow does.

      Set source_task_id to %s. Submit exactly one proposal, then stop: it does not take effect
      until the user approves it.
      """;

  private final Agent baseAgent;
  private final ProcedureService procedures;

  public ProcedureLearningUseCase(Agent baseAgent, ProcedureService procedures) {
    this.baseAgent = baseAgent;
    this.procedures = procedures;
  }

  @EventListener
  @Async
  public void onRepetitiveTaskCompleted(RepetitiveTaskCompleted event) {
    if (procedures.hasProposalFor(event.taskId())) {
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
