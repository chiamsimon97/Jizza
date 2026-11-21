package com.jizza.engine.workflow.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.jizza.engine.api.enums.NodeType;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.workflow.graph.WorkflowEdge;
import com.jizza.engine.api.workflow.graph.WorkflowGraph;
import com.jizza.engine.api.workflow.graph.WorkflowNode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Going back from a step that asks the person to an earlier step of the same run, to choose again
 * (another train or coach when the next page shows none available).
 *
 * <p>A step turns it on with {@code allowBack: true}. Its question then offers Back, to the step
 * named by {@code backTo}, or else to the steps of this run that asked the person before it, the
 * most recent first (and with none of those, to the step just before it). Going back runs that
 * step again, asking afresh with what the page lists now, and every step after it again too.
 * {@code backPages} (0 to 3) first goes back that many pages in the browser, for a step whose
 * list was on an earlier page.
 */
public final class GoBack {

  /** The answer's field naming the step to go back to (empty: the first one offered). */
  public static final String ANSWER = "back";
  /** On the step gone back to: ask the person even where it would have chosen by itself. */
  public static final String ASK_AGAIN = "askAgain";
  /** On the step gone back to: go back this many pages in the browser before it runs. */
  public static final String PAGES_BACK = "pagesBack";

  static final int MAX_PAGES_BACK = 3;
  private static final int MAX_OFFERED = 8;

  /** One step the person can go back to. */
  record Step(String node, String label) {}

  private GoBack() {}

  static boolean allowed(JsonNode config) {
    return config != null && config.path("allowBack").asBoolean(false);
  }

  /** How many pages to go back in the browser first, 0 to {@value #MAX_PAGES_BACK}. */
  static int pagesBack(JsonNode config) {
    if (config == null) return 0;
    return Math.max(0, Math.min(MAX_PAGES_BACK, config.path("backPages").asInt(0)));
  }

  /** The steps {@code node} offers to go back to, the one Back goes to first; empty for none. */
  static List<Step> offer(WorkflowGraph graph, RunContext context, WorkflowNode node) {
    if (node == null || !allowed(node.config())) return List.of();
    Set<String> before = upstream(graph, node.id());
    List<String> trail = new ArrayList<>(context.trail());
    trail.remove(node.id());
    trail.removeIf(id -> !before.contains(id) || !returnable(graph, id));

    String fixed = node.config().path("backTo").asText("");
    if (!fixed.isBlank() && trail.contains(fixed)) return List.of(step(graph, fixed));

    List<Step> out = new ArrayList<>();
    for (int i = trail.size() - 1; i >= 0 && out.size() < MAX_OFFERED; i--) {
      if (context.asked(trail.get(i))) out.add(step(graph, trail.get(i)));
    }
    if (out.isEmpty() && !trail.isEmpty()) out.add(step(graph, trail.get(trail.size() - 1)));
    return out;
  }

  /** What the question carries: the steps to go back to, as {node, label}; null for none. */
  static ArrayNode describe(List<Step> offered) {
    if (offered.isEmpty()) return null;
    ArrayNode out = Json.mapper().createArrayNode();
    offered.forEach(s -> out.addObject().put("node", s.node()).put("label", s.label()));
    return out;
  }

  /** {@code target} and every step after it, which run again once the person goes back. */
  static Set<String> stepsFrom(WorkflowGraph graph, String target) {
    Set<String> seen = new LinkedHashSet<>();
    Deque<String> queue = new ArrayDeque<>(List.of(target));
    while (!queue.isEmpty()) {
      String id = queue.poll();
      if (!seen.add(id)) continue;
      for (WorkflowEdge edge : graph.outgoing(id)) queue.add(edge.to());
    }
    return seen;
  }

  /** Every step with a path to {@code nodeId}. */
  private static Set<String> upstream(WorkflowGraph graph, String nodeId) {
    Set<String> seen = new LinkedHashSet<>();
    Deque<String> queue = new ArrayDeque<>();
    graph.incoming(nodeId).forEach(e -> queue.add(e.from()));
    while (!queue.isEmpty()) {
      String id = queue.poll();
      if (!seen.add(id)) continue;
      graph.incoming(id).forEach(e -> queue.add(e.from()));
    }
    return seen;
  }

  /** Steps that do something; the trigger and the plumbing between paths are never gone back to. */
  private static boolean returnable(WorkflowGraph graph, String id) {
    return graph.node(id).map(WorkflowNode::type).map(type -> type != NodeType.TRIGGER
        && type != NodeType.PARALLEL && type != NodeType.JOIN && type != NodeType.END)
        .orElse(false);
  }

  private static Step step(WorkflowGraph graph, String id) {
    String label = graph.node(id).map(n -> n.label() == null || n.label().isBlank() ? n.id()
        : n.label()).orElse(id);
    return new Step(id, label);
  }
}
