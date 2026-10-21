package com.jizza.engine.workflow.nodes;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.enums.NodeType;
import com.jizza.engine.api.error.SandboxViolationException;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.workflow.engine.ApprovalDecisions;
import com.jizza.engine.workflow.engine.NodeExecutor;
import com.jizza.engine.workflow.sandbox.JexlSandbox;
import com.jizza.engine.workflow.sandbox.Templates;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Pauses its path until the user approves or rejects, then takes the {@code approved} or
 * {@code rejected} path (Zapier's "Request approval", Zoho Flow's approval). Unlike a step that
 * asks permission for its own action, a rejection here is an answer, not a failure: the workflow
 * decides what happens next.
 *
 * <p>{@code prompt} is what the user is asked, with {@code {{ }}} placeholders. With a
 * {@code timeout} (ISO-8601, such as {@code P1D}), an unanswered request takes the
 * {@code onTimeout} path, rejected unless set otherwise. The deadline is fixed when the step is
 * first reached, so a restart does not extend it.
 */
@Component
public class ApprovalNodeExecutor implements NodeExecutor {

  static final String DEFAULT_PROMPT = "Approve this step so the workflow can continue?";

  private final JexlSandbox sandbox;
  private final Clock clock;

  @Autowired
  public ApprovalNodeExecutor(JexlSandbox sandbox) {
    this(sandbox, Clock.systemUTC());
  }

  public ApprovalNodeExecutor(JexlSandbox sandbox, Clock clock) {
    this.sandbox = sandbox;
    this.clock = clock;
  }

  @Override
  public NodeType type() { return NodeType.APPROVAL; }

  @Override
  public Outcome execute(Execution execution) {
    String nodeId = execution.node().id();
    var context = execution.context();
    var config = execution.node().config();

    String decision = ApprovalDecisions.of(context, nodeId);
    if (decision != null) {
      ObjectNode output = Json.obj().put("decision", decision).put("timedOut", false);
      String comment = context.state(nodeId).path("comment").asText(null);
      if (comment != null) output.put("comment", comment);
      return Outcome.branched(decision, output);
    }

    ObjectNode state = context.state(nodeId);
    Instant deadline = null;
    if (state.path("deadline").isTextual()) {
      deadline = Instant.parse(state.get("deadline").asText());
    } else if (config != null && config.hasNonNull("timeout")) {
      deadline = clock.instant().plus(Duration.parse(config.get("timeout").asText()));
      state.put("deadline", deadline.toString());
    }
    if (deadline != null && !clock.instant().isBefore(deadline)) {
      String onTimeout = config.path("onTimeout").asText("rejected");
      return Outcome.branched(onTimeout, Json.obj().put("decision", onTimeout).put("timedOut", true));
    }

    String prompt;
    try {
      prompt = Templates.render(config == null ? null : config.path("prompt").asText(null),
          sandbox, context.variables());
    } catch (SandboxViolationException e) {
      return Outcome.permanentFailure("The approval request could not be written: " + e.getMessage());
    }
    return Outcome.awaitApproval(prompt == null || prompt.isBlank() ? DEFAULT_PROMPT : prompt,
        deadline);
  }
}
