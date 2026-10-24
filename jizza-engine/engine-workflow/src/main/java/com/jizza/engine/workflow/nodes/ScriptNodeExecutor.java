package com.jizza.engine.workflow.nodes;

import com.jizza.engine.api.enums.NodeType;
import com.jizza.engine.api.error.SandboxViolationException;
import com.jizza.engine.api.workflow.functions.ScriptFunctions;
import com.jizza.engine.workflow.engine.NodeExecutor;
import com.jizza.engine.workflow.sandbox.JexlSandbox;
import com.jizza.engine.workflow.sandbox.ScriptStep;
import org.springframework.stereotype.Component;

/**
 * Runs a Script step (see {@link ScriptStep}): one of the run owner's functions, or a script saved
 * in the step. Like a transform, a sandbox violation or a script
 * error is a permanent failure: the same script fails the same way on a retry.
 */
@Component
public class ScriptNodeExecutor implements NodeExecutor {

  private final JexlSandbox sandbox;
  private final ScriptFunctions functions;

  public ScriptNodeExecutor(JexlSandbox sandbox, ScriptFunctions functions) {
    this.sandbox = sandbox;
    this.functions = functions;
  }

  @Override
  public NodeType type() { return NodeType.SCRIPT; }

  @Override
  public Outcome execute(Execution execution) {
    try {
      return Outcome.completed(ScriptStep.run(sandbox, functions, execution.userId(), execution.node().config(),
          execution.context().variables()));
    } catch (SandboxViolationException e) {
      return Outcome.permanentFailure(e.getMessage());
    }
  }
}
