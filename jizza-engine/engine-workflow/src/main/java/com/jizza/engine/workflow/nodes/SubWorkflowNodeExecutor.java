package com.jizza.engine.workflow.nodes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.enums.NodeType;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.SandboxViolationException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.persistence.entity.WorkflowRunEntity;
import com.jizza.engine.workflow.engine.NodeExecutor;
import com.jizza.engine.workflow.sandbox.JexlSandbox;
import com.jizza.engine.workflow.sandbox.Templates;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Runs another of the person's workflows as one step, and completes with what that run produced.
 *
 * <p>The child's input is its declared inputs mapped from this run ({@code inputs}, a value with
 * {@code {{ … }}} placeholders per input name; a blank one is left out so the child's default
 * applies), or {@code inputExpr} evaluated over this run, or the literal {@code input}, or nothing.
 * The child checks the inputs it declares, so a missing required one fails this step with its name. The child is started once and its id kept in the step's state, so a restart or a
 * retry reads the same child instead of starting another. While the child waits (on a person, a
 * timer, its own children) this path waits too, and the child finishing wakes it.
 */
@Component
public class SubWorkflowNodeExecutor implements NodeExecutor {

  private final ChildRuns children;
  private final JexlSandbox sandbox;

  public SubWorkflowNodeExecutor(ChildRuns children, JexlSandbox sandbox) {
    this.children = children;
    this.sandbox = sandbox;
  }

  @Override
  public NodeType type() { return NodeType.SUB_WORKFLOW; }

  @Override
  public Outcome execute(Execution execution) {
    ObjectNode state = execution.context().state(execution.node().id());
    if (state.path("children").isEmpty()) {
      UUID workflowId = ChildRuns.workflowIdOf(execution);
      if (workflowId == null) return Outcome.permanentFailure("Choose the workflow this step runs");
      JsonNode input;
      try {
        input = inputFor(execution);
      } catch (SandboxViolationException e) {
        return Outcome.permanentFailure(e.getMessage());
      }
      UUID childId;
      try {
        childId = children.start(execution, workflowId, input);
      } catch (ValidationException | NotFoundException e) {
        return Outcome.permanentFailure(e.getMessage());
      }
      state.putArray("children").addObject().put("runId", childId.toString());
    }

    UUID childId = UUID.fromString(state.path("children").get(0).path("runId").asText());
    Optional<WorkflowRunEntity> child = children.find(childId);
    if (child.isEmpty()) return Outcome.permanentFailure("The workflow this step ran is gone");
    return switch (child.get().getStatus()) {
      case DONE -> Outcome.completed(ChildRuns.resultOf(child.get()));
      case FAILED, CANCELLED -> Outcome.permanentFailure(
          "The workflow this step ran did not finish: " + ChildRuns.whyNot(child.get()));
      default -> Outcome.awaitChildren();
    };
  }

  private JsonNode inputFor(Execution execution) {
    JsonNode config = execution.node().config();
    if (config.path("inputs").isObject() && !config.path("inputs").isEmpty()) {
      ObjectNode input = Json.obj();
      for (var field : config.get("inputs").properties()) {
        String template = field.getValue().isTextual() ? field.getValue().asText() : null;
        if (template == null) {
          if (!field.getValue().isNull()) input.set(field.getKey(), field.getValue());
          continue;
        }
        if (template.isBlank()) continue;
        input.set(field.getKey(), Templates.renderValue(template, sandbox, execution.context().variables()));
      }
      return input;
    }
    if (config.hasNonNull("inputExpr")) {
      return sandbox.evaluate(config.get("inputExpr").asText(), execution.context().variables());
    }
    return config.hasNonNull("input") ? config.get("input") : Json.obj();
  }
}
