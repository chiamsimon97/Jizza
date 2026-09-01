package com.jizza.platform.usecase.procedure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jizza.engine.api.agent.Agent;
import com.jizza.engine.api.agent.AgentRequest;
import com.jizza.engine.api.agent.AgentResult;
import com.jizza.engine.api.event.RepetitiveTaskCompleted;
import com.jizza.engine.api.procedure.ProcedureService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Asking an agent to write down a repeated job -- once, and never for a job already captured. */
class ProcedureLearningUseCaseTest {

  private final UUID userId = UUID.randomUUID();
  private final UUID agentId = UUID.randomUUID();
  private final UUID taskId = UUID.randomUUID();
  private Agent baseAgent;
  private ProcedureService procedures;
  private ProcedureLearningUseCase useCase;

  private RepetitiveTaskCompleted event() {
    return new RepetitiveTaskCompleted(userId, agentId, taskId, "Weekly filing", "Filed everything.");
  }

  @BeforeEach
  void setUp() {
    baseAgent = mock(Agent.class);
    procedures = mock(ProcedureService.class);
    useCase = new ProcedureLearningUseCase(baseAgent, procedures);
  }

  @Test
  void asksTheOwningAgentToWriteDownWhatItDidWhenNoProposalExistsYet() {
    when(procedures.hasProposalFor(taskId)).thenReturn(false);
    when(baseAgent.run(any())).thenReturn(mock(AgentResult.class));

    useCase.onRepetitiveTaskCompleted(event());

    verify(baseAgent).run(org.mockito.ArgumentMatchers.argThat((AgentRequest req) ->
        req.userId().equals(userId) && req.agentId().equals(agentId)
            && req.instruction().contains("Weekly filing")
            && req.instruction().contains(taskId.toString())));
  }

  @Test
  void doesNotAskAgainWhenAProposalAlreadyExistsForThisJob() {
    when(procedures.hasProposalFor(taskId)).thenReturn(true);

    useCase.onRepetitiveTaskCompleted(event());

    verify(baseAgent, never()).run(any());
  }

  @Test
  void learningIsBestEffortAndDoesNotPropagateAFailure() {
    when(procedures.hasProposalFor(taskId)).thenReturn(false);
    when(baseAgent.run(any())).thenThrow(new RuntimeException("model unavailable"));

    assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
        useCase.onRepetitiveTaskCompleted(event()))).isNull();
  }
}
