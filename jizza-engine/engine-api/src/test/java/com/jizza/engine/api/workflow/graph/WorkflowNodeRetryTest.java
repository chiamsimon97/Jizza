package com.jizza.engine.api.workflow.graph;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jizza.engine.api.enums.NodeType;
import org.junit.jupiter.api.Test;

class WorkflowNodeRetryTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private static WorkflowNode node(String config) throws Exception {
    return new WorkflowNode("n1", NodeType.DEVICE_ACTION, "Click", JSON.readTree(config), null);
  }

  @Test
  void usesTheDefaultsWithoutAnyRetrySetting() throws Exception {
    assertThat(node("{}").retryOrDefault()).isEqualTo(WorkflowNode.RetryPolicy.defaults());
  }

  @Test
  void readsTheRetryTheBuilderSavesInConfig() throws Exception {
    var policy = node("{\"retry\":{\"maxAttempts\":5,\"backoffMillis\":2000}}").retryOrDefault();
    assertThat(policy.maxAttempts()).isEqualTo(5);
    assertThat(policy.backoffMillis()).isEqualTo(2000);
    assertThat(node("{\"retry\":{\"maxAttempts\":1}}").retryOrDefault().maxAttempts()).isEqualTo(1);
  }

  @Test
  void keepsConfiguredValuesInBounds() throws Exception {
    var policy = node("{\"retry\":{\"maxAttempts\":99,\"backoffMillis\":-5}}").retryOrDefault();
    assertThat(policy.maxAttempts()).isEqualTo(10);
    assertThat(policy.backoffMillis()).isZero();
    assertThat(node("{\"retry\":{\"maxAttempts\":0}}").retryOrDefault().maxAttempts()).isEqualTo(1);
  }

  @Test
  void aNodeLevelPolicyWins() throws Exception {
    var own = new WorkflowNode.RetryPolicy(2, 10, 1.0, 100);
    var n = new WorkflowNode("n1", NodeType.DEVICE_ACTION, "Click",
        JSON.readTree("{\"retry\":{\"maxAttempts\":7}}"), own);
    assertThat(n.retryOrDefault()).isEqualTo(own);
  }
}
