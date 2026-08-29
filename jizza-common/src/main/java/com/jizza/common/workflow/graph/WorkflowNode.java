package com.jizza.common.workflow.graph;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.common.enums.NodeType;

/**
 * One node of the persisted DAG.
 *
 * @param config node-kind specific settings (tool id, expression, agent id, branch predicate…)
 * @param retry  optional per-node retry policy; the engine falls back to its defaults when absent
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WorkflowNode(String id, NodeType type, String label, JsonNode config, RetryPolicy retry) {

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record RetryPolicy(int maxAttempts, long backoffMillis, double multiplier, long timeoutMillis) {
    public static RetryPolicy defaults() { return new RetryPolicy(3, 1000, 2.0, 60_000); }
  }

  public RetryPolicy retryOrDefault() { return retry == null ? RetryPolicy.defaults() : retry; }

  /** Nodes whose execution changes the outside world need an idempotency key (R31). */
  public boolean sideEffecting() {
    if (type == NodeType.CONNECTOR_TOOL) {
      return config == null || config.path("sideEffecting").asBoolean(true);
    }
    return false;
  }
}
