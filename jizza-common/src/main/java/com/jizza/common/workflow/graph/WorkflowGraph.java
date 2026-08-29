package com.jizza.common.workflow.graph;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.jizza.common.enums.NodeType;
import com.jizza.common.error.ValidationException;
import com.jizza.common.util.Json;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** The workflow DAG as stored in {@code workflows.graph}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WorkflowGraph(List<WorkflowNode> nodes, List<WorkflowEdge> edges) {

  public WorkflowGraph {
    nodes = nodes == null ? List.of() : List.copyOf(nodes);
    edges = edges == null ? List.of() : List.copyOf(edges);
  }

  public static WorkflowGraph from(com.fasterxml.jackson.databind.JsonNode json) {
    if (json == null || json.isNull()) throw new ValidationException("Workflow graph is missing");
    return Json.convert(json, WorkflowGraph.class);
  }

  public com.fasterxml.jackson.databind.JsonNode toJson() { return Json.toNode(this); }

  public Optional<WorkflowNode> node(String id) {
    return nodes.stream().filter(n -> n.id().equals(id)).findFirst();
  }

  public WorkflowNode requireNode(String id) {
    return node(id).orElseThrow(() -> new ValidationException("Unknown node '" + id + "'"));
  }

  /** The single entry node. */
  public WorkflowNode trigger() {
    return nodes.stream().filter(n -> n.type() == NodeType.TRIGGER).findFirst()
        .orElseThrow(() -> new ValidationException("Workflow has no trigger node"));
  }

  public List<WorkflowEdge> outgoing(String nodeId) {
    return edges.stream().filter(e -> e.from().equals(nodeId)).toList();
  }

  /** Next node for a plain (non-branching) step. */
  public Optional<String> next(String nodeId) {
    return outgoing(nodeId).stream().map(WorkflowEdge::to).findFirst();
  }

  public Optional<String> next(String nodeId, String branch) {
    return outgoing(nodeId).stream()
        .filter(e -> branch == null ? e.branch() == null : branch.equals(e.branch()))
        .map(WorkflowEdge::to).findFirst();
  }

  /**
   * Structural validation applied before a workflow may be saved or run: unique ids, exactly one
   * trigger, no dangling edges, and no cycles (the engine executes a DAG, not a loop).
   */
  public void validate() {
    if (nodes.isEmpty()) throw new ValidationException("Workflow has no nodes");
    Set<String> ids = new HashSet<>();
    long triggers = 0;
    for (WorkflowNode n : nodes) {
      if (n.id() == null || n.id().isBlank()) throw new ValidationException("Node without an id");
      if (!ids.add(n.id())) throw new ValidationException("Duplicate node id '" + n.id() + "'");
      if (n.type() == null) throw new ValidationException("Node '" + n.id() + "' has no type");
      if (n.type() == NodeType.TRIGGER) triggers++;
    }
    if (triggers != 1) throw new ValidationException("Workflow must have exactly one trigger node");
    for (WorkflowEdge e : edges) {
      if (!ids.contains(e.from())) throw new ValidationException("Edge from unknown node '" + e.from() + "'");
      if (!ids.contains(e.to())) throw new ValidationException("Edge to unknown node '" + e.to() + "'");
    }
    assertAcyclic();
  }

  private void assertAcyclic() {
    Map<String, List<String>> adj = new LinkedHashMap<>();
    Map<String, Integer> indeg = new HashMap<>();
    nodes.forEach(n -> { adj.put(n.id(), new ArrayList<>()); indeg.put(n.id(), 0); });
    for (WorkflowEdge e : edges) {
      adj.get(e.from()).add(e.to());
      indeg.merge(e.to(), 1, Integer::sum);
    }
    var queue = new java.util.ArrayDeque<String>();
    indeg.forEach((k, v) -> { if (v == 0) queue.add(k); });
    int visited = 0;
    while (!queue.isEmpty()) {
      String cur = queue.poll();
      visited++;
      for (String nxt : adj.get(cur)) {
        if (indeg.merge(nxt, -1, Integer::sum) == 0) queue.add(nxt);
      }
    }
    if (visited != nodes.size()) throw new ValidationException("Workflow graph contains a cycle");
  }

  /** Stable fingerprint of the graph, used to prove a bound job ran the approved procedure (R36). */
  public String fingerprint() {
    StringBuilder sb = new StringBuilder();
    nodes.stream().sorted(java.util.Comparator.comparing(WorkflowNode::id)).forEach(n ->
        sb.append(n.id()).append('|').append(n.type()).append('|')
          .append(n.config() == null || n.config().isNull() ? "" : Json.write(n.config())).append('\n'));
    edges.stream()
        .sorted(java.util.Comparator.comparing(WorkflowEdge::from).thenComparing(WorkflowEdge::to,
            java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder())))
        .forEach(e -> sb.append(e.from()).append("->").append(e.to())
            .append('#').append(e.branch() == null ? "" : e.branch()).append('\n'));
    return sha256Hex(sb.toString());
  }

  private static String sha256Hex(String s) {
    try {
      var md = java.security.MessageDigest.getInstance("SHA-256");
      byte[] d = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(d.length * 2);
      for (byte b : d) hex.append(String.format("%02x", b));
      return hex.toString();
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
