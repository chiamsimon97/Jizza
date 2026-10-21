package com.jizza.engine.workflow.nodes;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.enums.NodeType;
import com.jizza.engine.api.error.SandboxViolationException;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.workflow.engine.NodeExecutor;
import com.jizza.engine.workflow.sandbox.JexlSandbox;
import org.springframework.stereotype.Component;

/**
 * Takes the first of several paths whose condition holds, or the {@code else} path when none does
 * (Zapier's Paths, Zoho Flow's Decision). {@code cases} is a list of {@code {id, label, condition}};
 * each connection out of the step names the case id it follows. Only one path is taken, and the
 * choice is checkpointed like a branch's, so a resumed run does not decide again.
 */
@Component
public class SwitchNodeExecutor implements NodeExecutor {

  public static final String ELSE = "else";

  private final JexlSandbox sandbox;

  public SwitchNodeExecutor(JexlSandbox sandbox) {
    this.sandbox = sandbox;
  }

  @Override
  public NodeType type() { return NodeType.SWITCH; }

  @Override
  public Outcome execute(Execution execution) {
    JsonNode cases = execution.node().config() == null
        ? null : execution.node().config().get("cases");
    if (cases == null || !cases.isArray()) return Outcome.permanentFailure("The step has no paths");
    var variables = execution.context().variables();
    for (JsonNode c : cases) {
      String id = c.path("id").asText();
      try {
        if (sandbox.evaluate(c.path("condition").asText(null), variables).asBoolean(false)) {
          return Outcome.branched(id, Json.obj().put("path", id)
              .put("label", c.path("label").asText(id)));
        }
      } catch (SandboxViolationException e) {
        return Outcome.permanentFailure("Path '" + c.path("label").asText(id) + "': "
            + e.getMessage());
      }
    }
    return Outcome.branched(ELSE, Json.obj().put("path", ELSE).put("label", "Otherwise"));
  }
}
