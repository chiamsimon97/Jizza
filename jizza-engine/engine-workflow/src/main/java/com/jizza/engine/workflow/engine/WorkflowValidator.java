package com.jizza.engine.workflow.engine;

import com.jizza.engine.api.enums.NodeType;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.workflow.graph.WorkflowEdge;
import com.jizza.engine.api.workflow.graph.WorkflowGraph;
import com.jizza.engine.api.workflow.graph.WorkflowNode;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Structural checks beyond {@link WorkflowGraph#validate()}: the things that would only surface as
 * a confusing failure halfway through a run.
 */
@Component
public class WorkflowValidator {

  public void validate(WorkflowGraph graph) {
    graph.validate();

    for (WorkflowNode node : graph.nodes()) {
      switch (node.type()) {
        case TRANSFORM -> requireConfig(node, "expression");
        case BRANCH -> {
          requireConfig(node, "condition");
          List<WorkflowEdge> outgoing = graph.outgoing(node.id());
          boolean hasTrue = outgoing.stream().anyMatch(e -> "true".equals(e.branch()));
          boolean hasFalse = outgoing.stream().anyMatch(e -> "false".equals(e.branch()));
          if (!hasTrue || !hasFalse) {
            throw new ValidationException("Branch node '" + node.id()
                + "' needs an outgoing edge for both 'true' and 'false'");
          }
        }
        case CONNECTOR_TOOL -> requireConfig(node, "toolId");
        case AGENT_STEP -> {
          requireConfig(node, "agentId");
          if (node.config() == null
              || (!node.config().hasNonNull("instruction")
                  && !node.config().hasNonNull("instructionExpr"))) {
            throw new ValidationException("Agent step '" + node.id()
                + "' needs an 'instruction' or 'instructionExpr'");
          }
        }
        case PARALLEL -> {
          if (graph.outgoing(node.id()).size() < 2) {
            throw new ValidationException("Parallel step '" + node.id()
                + "' needs at least two paths leaving it");
          }
        }
        case WAIT -> validateWait(node);
        case SUB_WORKFLOW -> requireWorkflowId(node);
        case FOR_EACH -> {
          requireWorkflowId(node);
          requireConfig(node, "items");
        }
        case APPROVAL -> validateApproval(graph, node);
        case HTTP_REQUEST -> validateHttp(node);
        case FILTER -> requireConfig(node, "condition");
        case SWITCH -> validateSwitch(graph, node);
        case SET_VARIABLE -> validateSetVariable(node);
        case STORAGE -> validateStorage(node);
        case NOTIFY -> {
          requireConfig(node, "message");
          String channel = node.config().path("channel").asText("app");
          if (!List.of("app", "email", "both").contains(channel)) {
            throw new ValidationException("Notify step '" + node.id()
                + "' has channel '" + channel + "'; use app, email or both");
          }
        }
        default -> { /* Trigger, intervention, join and end nodes need no extra configuration. */ }
      }

      if (!FORKS.contains(node.type()) && graph.outgoing(node.id()).size() > 1) {
        throw new ValidationException("Node '" + node.id()
            + "' has more than one outgoing edge; only a branch, paths, approval or parallel "
            + "step may fork");
      }
    }
  }

  private static final java.util.Set<NodeType> FORKS = java.util.Set.of(NodeType.END,
      NodeType.BRANCH, NodeType.PARALLEL, NodeType.SWITCH, NodeType.APPROVAL);
  private static final java.util.Set<String> HTTP_METHODS =
      java.util.Set.of("GET", "POST", "PUT", "PATCH", "DELETE");
  private static final java.util.Set<String> STORAGE_ACTIONS =
      java.util.Set.of("get", "set", "increment", "append", "delete");
  private static final java.util.regex.Pattern VARIABLE_NAME =
      java.util.regex.Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,63}");
  static final int MAX_SWITCH_CASES = 10;

  private void validateApproval(WorkflowGraph graph, WorkflowNode node) {
    var config = node.config();
    if (config != null && config.hasNonNull("timeout")) {
      try {
        if (java.time.Duration.parse(config.get("timeout").asText()).isNegative()) {
          throw new java.time.format.DateTimeParseException("", "", 0);
        }
      } catch (java.time.format.DateTimeParseException e) {
        throw new ValidationException("Approval step '" + node.id() + "' has a timeout that is "
            + "not an ISO-8601 duration such as P1D or PT4H");
      }
    }
    String onTimeout = config == null ? "rejected" : config.path("onTimeout").asText("rejected");
    if (!onTimeout.equals("approved") && !onTimeout.equals("rejected")) {
      throw new ValidationException("Approval step '" + node.id()
          + "' must take the approved or rejected path on timeout");
    }
    List<WorkflowEdge> outgoing = graph.outgoing(node.id());
    for (WorkflowEdge edge : outgoing) {
      if (!"approved".equals(edge.branch()) && !"rejected".equals(edge.branch())) {
        throw new ValidationException("Approval step '" + node.id()
            + "' has a path that is neither 'approved' nor 'rejected'");
      }
    }
    if (outgoing.stream().noneMatch(e -> "approved".equals(e.branch()))) {
      throw new ValidationException("Approval step '" + node.id() + "' needs an 'approved' path");
    }
  }

  private void validateHttp(WorkflowNode node) {
    requireConfig(node, "url");
    String url = node.config().get("url").asText().strip();
    if (!url.startsWith("https://") && !url.startsWith("http://") && !url.startsWith("{{")) {
      throw new ValidationException("Webhook step '" + node.id()
          + "' needs a URL starting with https://");
    }
    String method = node.config().path("method").asText("POST").toUpperCase(java.util.Locale.ROOT);
    if (!HTTP_METHODS.contains(method)) {
      throw new ValidationException("Webhook step '" + node.id() + "' has method '" + method
          + "'; use GET, POST, PUT, PATCH or DELETE");
    }
    var headers = node.config().get("headers");
    if (headers != null && !headers.isNull() && !headers.isObject()) {
      throw new ValidationException("Webhook step '" + node.id()
          + "' needs its headers as name and value pairs");
    }
  }

  private void validateSwitch(WorkflowGraph graph, WorkflowNode node) {
    var cases = node.config() == null ? null : node.config().get("cases");
    if (cases == null || !cases.isArray() || cases.isEmpty()) {
      throw new ValidationException("Paths step '" + node.id() + "' needs at least one path");
    }
    if (cases.size() > MAX_SWITCH_CASES) {
      throw new ValidationException("Paths step '" + node.id() + "' has more than "
          + MAX_SWITCH_CASES + " paths");
    }
    java.util.Set<String> ids = new java.util.HashSet<>();
    for (var c : cases) {
      String id = c.path("id").asText("");
      if (id.isBlank() || id.equals("else") || !ids.add(id)) {
        throw new ValidationException("Paths step '" + node.id()
            + "' needs a distinct id for every path, and 'else' is taken");
      }
      if (c.path("condition").asText("").isBlank()) {
        throw new ValidationException("Path '" + id + "' of step '" + node.id()
            + "' needs a condition");
      }
    }
    for (WorkflowEdge edge : graph.outgoing(node.id())) {
      if (!"else".equals(edge.branch()) && !ids.contains(edge.branch())) {
        throw new ValidationException("Paths step '" + node.id() + "' has a connection for '"
            + edge.branch() + "', which is not one of its paths");
      }
    }
  }

  private void validateSetVariable(WorkflowNode node) {
    var variables = node.config() == null ? null : node.config().get("variables");
    if (variables == null || !variables.isArray() || variables.isEmpty()) {
      throw new ValidationException("Set variable step '" + node.id()
          + "' needs at least one variable");
    }
    for (var v : variables) {
      String name = v.path("name").asText("");
      if (!VARIABLE_NAME.matcher(name).matches()) {
        throw new ValidationException("Set variable step '" + node.id() + "' has a variable named '"
            + name + "'; use letters, digits and _ and start with a letter");
      }
      if (v.path("expr").asText("").isBlank()) {
        throw new ValidationException("Variable '" + name + "' of step '" + node.id()
            + "' needs an expression");
      }
    }
  }

  private void validateStorage(WorkflowNode node) {
    requireConfig(node, "key");
    String action = node.config().path("action").asText("get");
    if (!STORAGE_ACTIONS.contains(action)) {
      throw new ValidationException("Storage step '" + node.id() + "' has action '" + action
          + "'; use get, set, increment, append or delete");
    }
    if ((action.equals("set") || action.equals("append"))
        && node.config().path("valueExpr").asText("").isBlank()) {
      throw new ValidationException("Storage step '" + node.id() + "' needs a valueExpr to "
          + action);
    }
  }

  private void validateWait(WorkflowNode node) {
    var config = node.config();
    boolean duration = config != null && config.hasNonNull("duration");
    if (!duration && (config == null
        || (!config.hasNonNull("until") && !config.hasNonNull("untilExpr")))) {
      throw new ValidationException("Wait step '" + node.id()
          + "' needs a 'duration', an 'until' time or an 'untilExpr'");
    }
    if (duration) {
      try {
        java.time.Duration parsed = java.time.Duration.parse(config.get("duration").asText());
        if (parsed.isNegative()) throw new java.time.format.DateTimeParseException("", "", 0);
      } catch (java.time.format.DateTimeParseException e) {
        throw new ValidationException("Wait step '" + node.id() + "' has a duration that is not "
            + "an ISO-8601 duration such as PT30M or P1D");
      }
    }
  }

  private void requireWorkflowId(WorkflowNode node) {
    requireConfig(node, "workflowId");
    try {
      java.util.UUID.fromString(node.config().get("workflowId").asText());
    } catch (IllegalArgumentException e) {
      throw new ValidationException(node.type() + " node '" + node.id()
          + "' names a workflow id that is not valid");
    }
  }

  private void requireConfig(WorkflowNode node, String key) {
    if (node.config() == null || !node.config().hasNonNull(key)) {
      throw new ValidationException(node.type() + " node '" + node.id() + "' needs a '" + key + "'");
    }
  }
}
