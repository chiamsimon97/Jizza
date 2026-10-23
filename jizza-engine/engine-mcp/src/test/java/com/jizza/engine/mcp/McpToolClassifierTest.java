package com.jizza.engine.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.jizza.engine.api.enums.ActionType;
import org.junit.jupiter.api.Test;

/** What a tool probably does, from its name, so an agent's guardrails apply to it at once. */
class McpToolClassifierTest {

  @Test
  void kitesToolsGetWhatTheyDo() {
    assertThat(McpToolClassifier.suggest("get_holdings", null)).contains(ActionType.READ);
    assertThat(McpToolClassifier.suggest("get_orders", null)).contains(ActionType.READ);
    assertThat(McpToolClassifier.suggest("search_instruments", null)).contains(ActionType.READ);
    assertThat(McpToolClassifier.suggest("login", null)).contains(ActionType.READ);
    assertThat(McpToolClassifier.suggest("place_order", null)).contains(ActionType.MONEY);
    assertThat(McpToolClassifier.suggest("modify_order", null)).contains(ActionType.MONEY);
    assertThat(McpToolClassifier.suggest("cancel_order", null)).contains(ActionType.MONEY);
    assertThat(McpToolClassifier.suggest("delete_gtt_order", null)).contains(ActionType.MONEY);
  }

  @Test
  void otherServersToo() {
    assertThat(McpToolClassifier.suggest("listIssues", null)).contains(ActionType.READ);
    assertThat(McpToolClassifier.suggest("create-page", null)).contains(ActionType.CREATE);
    assertThat(McpToolClassifier.suggest("update_record", null)).contains(ActionType.UPDATE);
    assertThat(McpToolClassifier.suggest("delete_file", null)).contains(ActionType.DELETE);
    assertThat(McpToolClassifier.suggest("send_email", null)).contains(ActionType.SEND);
  }

  @Test
  void theServersWordOnlyFillsAGapOrRaisesTheStakes() {
    assertThat(McpToolClassifier.suggest("holdings", "read-only")).contains(ActionType.READ);
    assertThat(McpToolClassifier.suggest("holdings", null)).isEmpty();
    assertThat(McpToolClassifier.suggest("get_and_wipe", "destructive")).contains(ActionType.DELETE);
    assertThat(McpToolClassifier.suggest("update_x", "read-only")).contains(ActionType.UPDATE);
  }
}
