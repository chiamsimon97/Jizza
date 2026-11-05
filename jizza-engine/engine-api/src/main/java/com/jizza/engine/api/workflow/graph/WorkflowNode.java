package com.jizza.engine.api.workflow.graph;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.enums.NodeType;

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

  /**
   * The node's retry policy: its own {@code retry}, else {@code config.retry} (what the builder
   * saves: {@code maxAttempts}, {@code backoffMillis}), else the engine's defaults. Attempts are
   * kept between 1 and 10 and the wait between tries between 0 and 10 minutes.
   */
  public RetryPolicy retryOrDefault() {
    if (retry != null) return retry;
    JsonNode configured = config == null ? null : config.path("retry");
    if (configured == null || !configured.isObject()) return RetryPolicy.defaults();
    RetryPolicy defaults = RetryPolicy.defaults();
    int attempts = Math.max(1, Math.min(10, configured.path("maxAttempts").asInt(defaults.maxAttempts())));
    long backoff = Math.max(0, Math.min(600_000,
        configured.path("backoffMillis").asLong(defaults.backoffMillis())));
    double multiplier = Math.max(1.0, Math.min(10.0,
        configured.path("multiplier").asDouble(defaults.multiplier())));
    return new RetryPolicy(attempts, backoff, multiplier, Math.max(backoff, defaults.timeoutMillis()));
  }

  /** Nodes whose execution changes the outside world need an idempotency key (R31). */
  public boolean sideEffecting() {
    if (type == NodeType.CONNECTOR_TOOL) {
      return config == null || config.path("sideEffecting").asBoolean(true);
    }
    return type == NodeType.MCP_TOOL;
  }
}
