package com.jizza.engine.webauto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.dto.WorkflowDto;
import com.jizza.engine.api.enums.WorkflowStatus;
import com.jizza.engine.api.webauto.Recordings.Linked;
import com.jizza.engine.api.workflow.graph.ModuleContract;
import com.jizza.engine.api.workflow.graph.WorkflowGraph;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The workflows that run a recording as one of their steps (Run a workflow, For each), and how
 * their steps' inputs match the recording's inputs now. Those steps always run the recording's
 * current version; what can go stale after an edit is the inputs they give it.
 */
final class LinkedWorkflows {

  private static final Set<String> RUNS_A_WORKFLOW = Set.of("SUB_WORKFLOW", "FOR_EACH");

  private LinkedWorkflows() {}

  static List<Linked> find(WorkflowDto module, List<WorkflowDto> candidates) {
    List<ModuleContract.Field> inputs = inputsOf(module);
    List<Linked> out = new ArrayList<>();
    for (WorkflowDto workflow : candidates) {
      if (workflow.id().equals(module.id()) || workflow.graph() == null) continue;
      List<String> labels = new ArrayList<>();
      Set<String> missing = new LinkedHashSet<>();
      Set<String> unknown = new LinkedHashSet<>();
      for (JsonNode node : workflow.graph().path("nodes")) {
        if (!runs(node, module)) continue;
        labels.add(node.path("label").asText(node.path("id").asText()));
        JsonNode given = node.path("config").path("inputs");
        boolean byName = given.isObject();
        Set<String> names = new LinkedHashSet<>();
        for (ModuleContract.Field f : inputs) {
          names.add(f.name());
          // For each gives each item as the input; its own names are only extra inputs.
          if (byName && !given.has(f.name()) && "SUB_WORKFLOW".equals(node.path("type").asText())) {
            missing.add(f.name());
          }
        }
        if (byName) given.fieldNames().forEachRemaining(n -> { if (!names.contains(n)) unknown.add(n); });
      }
      if (labels.isEmpty()) continue;
      boolean draft = workflow.status() == WorkflowStatus.DRAFT;
      String note = draft ? null : "It is " + workflow.status().name().toLowerCase().replace('_', ' ')
          + ", so changes to it go through review";
      out.add(new Linked(workflow.id(), workflow.name(), workflow.status().name(), labels,
          List.copyOf(missing), List.copyOf(unknown), draft && !(missing.isEmpty() && unknown.isEmpty()),
          note));
    }
    return out;
  }

  /**
   * The workflow's graph with its steps that run {@code module} given the module's inputs now,
   * or null when nothing changes. A new input starts empty, so the recording's default is used
   * until the person maps a value in the builder.
   */
  static ObjectNode update(WorkflowDto module, JsonNode graph) {
    ObjectNode copy = graph.deepCopy();
    List<ModuleContract.Field> inputs = inputsOf(module);
    Set<String> names = new LinkedHashSet<>();
    inputs.forEach(f -> names.add(f.name()));
    boolean changed = false;
    for (JsonNode node : copy.path("nodes")) {
      if (!runs(node, module) || !node.path("config").path("inputs").isObject()) continue;
      ObjectNode given = (ObjectNode) node.path("config").get("inputs");
      List<String> stale = new ArrayList<>();
      given.fieldNames().forEachRemaining(n -> { if (!names.contains(n)) stale.add(n); });
      if (!stale.isEmpty()) {
        given.remove(stale);
        changed = true;
      }
      if ("FOR_EACH".equals(node.path("type").asText())) continue;
      for (String name : names) {
        if (!given.has(name)) {
          given.put(name, "");
          changed = true;
        }
      }
    }
    return changed ? copy : null;
  }

  private static boolean runs(JsonNode node, WorkflowDto module) {
    return RUNS_A_WORKFLOW.contains(node.path("type").asText())
        && module.id().toString().equals(node.path("config").path("workflowId").asText());
  }

  private static List<ModuleContract.Field> inputsOf(WorkflowDto module) {
    try {
      return ModuleContract.inputsOf(WorkflowGraph.from(module.graph()));
    } catch (RuntimeException e) {
      return List.of();
    }
  }
}
