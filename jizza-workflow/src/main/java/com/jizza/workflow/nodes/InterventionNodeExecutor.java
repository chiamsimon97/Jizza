package com.jizza.workflow.nodes;

import com.jizza.common.enums.NodeType;
import com.jizza.common.util.Json;
import com.jizza.workflow.engine.NodeExecutor;
import org.springframework.stereotype.Component;

/**
 * The human-intervention node (R33). On first encounter it stops the run; once the user's
 * instruction has been recorded in the context, the same node completes and feeds that instruction
 * downstream.
 *
 * <p>Both halves live here so that a restart between them changes nothing: the decision is made
 * entirely from persisted state.
 */
@Component
public class InterventionNodeExecutor implements NodeExecutor {

  @Override
  public NodeType type() { return NodeType.HUMAN_INTERVENTION; }

  @Override
  public Outcome execute(Execution execution) {
    String instruction = execution.context().instructionFor(execution.node().id());
    if (instruction == null) {
      String prompt = execution.node().config() == null
          ? null : execution.node().config().path("prompt").asText(null);
      return Outcome.awaitInstruction(prompt == null
          ? "This run needs an instruction from you to continue."
          : prompt);
    }
    return Outcome.completed(Json.obj().put("instruction", instruction));
  }
}
