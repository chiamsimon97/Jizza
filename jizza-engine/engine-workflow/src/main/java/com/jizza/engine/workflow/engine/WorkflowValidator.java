package com.jizza.engine.workflow.engine;

import com.jizza.engine.api.enums.NodeType;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.workflow.graph.WorkflowEdge;
import com.jizza.engine.api.workflow.graph.WorkflowGraph;
import com.jizza.engine.api.workflow.graph.WorkflowNode;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Structural checks beyond {@link WorkflowGraph#validate()}: the things that would only surface as
 * a confusing failure halfway through a run.
 */
@Component
public class WorkflowValidator {

  private ObjectProvider<com.jizza.engine.api.device.DeviceActionCatalog> deviceActions;

  @Autowired(required = false)
  public void setDeviceActions(
      ObjectProvider<com.jizza.engine.api.device.DeviceActionCatalog> deviceActions) {
    this.deviceActions = deviceActions;
  }

  public void validate(WorkflowGraph graph) {
    graph.validate();
    com.jizza.engine.api.workflow.graph.ModuleContract.validate(graph);

    for (WorkflowNode node : graph.nodes()) {
      switch (node.type()) {
        case TRANSFORM -> requireConfig(node, "expression");
        case SCRIPT -> {
          if (node.config() == null || !node.config().hasNonNull("functionId")) requireConfig(node, "script");
        }
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
        case MCP_TOOL -> {
          requireConfig(node, "serverId");
          requireConfig(node, "tool");
        }
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
        case JOIN -> validateJoin(node);
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
        case DEVICE_ACTION -> {
          requireConfig(node, "action");
          boolean web = "web".equals(node.config().path("preset").asText())
              || node.config().path("action").asText().startsWith("browser.");
          if (web) {
            validateWebAction(node);
          }
          if (!(web && "sameBrowser".equals(node.config().path("target").asText()))) {
            try {
              com.jizza.engine.api.device.DeviceTarget.from(node.config().get("target"));
            } catch (IllegalArgumentException e) {
              throw new ValidationException("Device step '" + node.id()
                  + "' names a device that is not a device id");
            }
          }
        }
        case NOTIFY -> {
          requireConfig(node, "message");
          String channel = node.config().path("channel").asText("app");
          if (!List.of("app", "email", "both").contains(channel)) {
            throw new ValidationException("Notify step '" + node.id()
                + "' has channel '" + channel + "'; use app, email or both");
          }
        }
        default -> { /* Trigger, intervention and end nodes need no extra configuration. */ }
      }

      if (com.jizza.engine.api.workflow.graph.CheckSteps.isCheck(node)) {
        // A check step may fork once: its usual way out (yes) and one marked "false" (no).
        List<WorkflowEdge> out = graph.outgoing(node.id());
        long no = out.stream().filter(e -> "false".equals(e.branch())).count();
        long other = out.stream().filter(e -> e.branch() != null && !"false".equals(e.branch())
            && !"true".equals(e.branch())).count();
        if (no > 1 || other > 0 || out.size() - no > 1) {
          throw new ValidationException("Check step '" + node.id()
              + "' may have one way out for yes and one for no");
        }
      } else if (!FORKS.contains(node.type()) && graph.outgoing(node.id()).size() > 1) {
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
  private static final java.util.regex.Pattern WHOLE_TEMPLATE =
      java.util.regex.Pattern.compile("\\{\\{(?:(?!\\{\\{|}})[\\s\\S])+}}");
  static final int MAX_SWITCH_CASES = 10;
  private static final java.util.Set<String> WEB_ELEMENT_ACTIONS = java.util.Set.of(
      "browser.click", "browser.hover", "browser.setText", "browser.getText",
      "browser.setCheckbox", "browser.getCheckbox", "browser.selectItem",
      "browser.extractTable", "browser.typeInto", "browser.exists", "browser.setDate",
      "browser.checkItems", "browser.listItems", "browser.clickItem", "browser.pickDate",
      "browser.chooseOption");

  private void validateWebAction(WorkflowNode node) {
    String action = node.config().path("action").asText();
    int version = node.config().path("version").asInt(0);
    var catalog = deviceActions == null ? null : deviceActions.getIfAvailable();
    var spec = catalog == null ? java.util.Optional
        .<com.jizza.engine.api.device.DeviceActionSpec>empty() : catalog.resolve(action, version);
    if (!action.startsWith("browser.") || spec.isEmpty()
        || !"browser".equals(spec.get().capability())) {
      throw new ValidationException("Web step '" + node.id()
          + "' needs an available browser action");
    }
    var inputs = node.config().path("inputs");
    validateRequiredInputs(node, spec.get().input(), inputs);
    var element = inputs.get("element");
    if (element == null && !WEB_ELEMENT_ACTIONS.contains(action)) return;
    boolean locator = element != null && element.isObject()
        && element.path("strategies").isArray() && !element.path("strategies").isEmpty();
    boolean template = element != null && element.isTextual()
        && WHOLE_TEMPLATE.matcher(element.asText().trim()).matches();
    if (!locator && !template) {
      throw new ValidationException("Pick the element this step works on.");
    }
  }

  private void validateRequiredInputs(WorkflowNode node, com.fasterxml.jackson.databind.JsonNode schema,
                                      com.fasterxml.jackson.databind.JsonNode inputs) {
    if (schema == null || !schema.isObject()) return;
    var required = schema.path("required");
    if (required.isArray()) {
      for (var field : required) {
        if ("element".equals(field.asText())) continue;
        if (!hasInput(inputs, field.asText())) {
          throw new ValidationException("Web step '" + node.id()
              + "' needs the input '" + field.asText() + "'");
        }
      }
    }
    var choices = schema.path("oneOf");
    if (choices.isArray() && !choices.isEmpty()) {
      int matches = 0;
      for (var choice : choices) {
        boolean allPresent = true;
        for (var field : choice.path("required")) {
          if (!hasInput(inputs, field.asText())) allPresent = false;
        }
        if (allPresent) matches++;
      }
      if (matches != 1) {
        throw new ValidationException("Web step '" + node.id()
            + "' needs one of the catalog's input choices");
      }
    }
  }

  private boolean hasInput(com.fasterxml.jackson.databind.JsonNode inputs, String field) {
    var value = inputs == null ? null : inputs.get(field);
    return value != null && !value.isNull() && (!value.isTextual() || !value.asText().isBlank());
  }

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
    for (String pairs : List.of("headers", "queryParams", "fields")) {
      var value = node.config().get(pairs);
      if (value != null && !value.isNull() && !value.isObject()) {
        throw new ValidationException("Webhook step '" + node.id()
            + "' needs its " + (pairs.equals("queryParams") ? "query parameters" : pairs)
            + " as name and value pairs");
      }
    }
    String bodyType = node.config().path("bodyType").asText("");
    if (!List.of("", "json", "form", "raw", "expression", "none").contains(bodyType)) {
      throw new ValidationException("Webhook step '" + node.id() + "' has body type '" + bodyType
          + "'; use json, form, raw, expression or none");
    }
    if (bodyType.equals("expression")) requireConfig(node, "bodyExpr");
    String responseType = node.config().path("responseType").asText("auto");
    if (!List.of("auto", "json", "text").contains(responseType)) {
      throw new ValidationException("Webhook step '" + node.id() + "' has response type '"
          + responseType + "'; use auto, json or text");
    }
    var auth = node.config().path("auth");
    String authType = auth.path("type").asText("none");
    String missing = switch (authType) {
      case "none" -> null;
      case "basic" -> auth.path("username").asText("").isBlank() ? "a username" : null;
      case "bearer" -> auth.path("token").asText("").isBlank() ? "a token" : null;
      case "apiKey" -> auth.path("name").asText("").isBlank() ? "the API key's name" : null;
      default -> throw new ValidationException("Webhook step '" + node.id() + "' has sign-in '"
          + authType + "'; use none, basic, bearer or apiKey");
    };
    if (missing != null) {
      throw new ValidationException("Webhook step '" + node.id() + "' needs " + missing);
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

  private void validateJoin(WorkflowNode node) {
    var config = node.config();
    if (config == null) return;
    if (config.hasNonNull("timeout") && !"none".equals(config.get("timeout").asText())) {
      try {
        java.time.Duration parsed = java.time.Duration.parse(config.get("timeout").asText());
        if (parsed.isNegative() || parsed.isZero()) {
          throw new java.time.format.DateTimeParseException("", "", 0);
        }
      } catch (java.time.format.DateTimeParseException e) {
        throw new ValidationException("Join step '" + node.id() + "' has a timeout that is not "
            + "an ISO-8601 duration such as PT30M or P1D (or \"none\" to wait for ever)");
      }
    }
    if (config.hasNonNull("onTimeout")) {
      String onTimeout = config.get("onTimeout").asText();
      if (!"continue".equals(onTimeout) && !"fail".equals(onTimeout)) {
        throw new ValidationException("Join step '" + node.id()
            + "' has onTimeout '" + onTimeout + "'; use \"continue\" or \"fail\"");
      }
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
