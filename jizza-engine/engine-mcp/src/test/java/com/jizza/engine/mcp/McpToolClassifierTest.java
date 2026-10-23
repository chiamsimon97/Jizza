package com.jizza.engine.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.jizza.engine.api.enums.ActionType;
import org.junit.jupiter.api.Test;

/** What a tool probably does, from its name and description, so guardrails apply at once. */
class McpToolClassifierTest {

  @Test
  void kitesToolsGetWhatTheyDo() {
    for (String read : new String[] {"get_holdings", "get_ltp", "get_margins", "get_orders",
        "get_order_trades", "get_order_history", "get_gtts", "get_mf_holdings", "get_quotes",
        "get_historical_data", "search_instruments", "get_profile", "login"}) {
      assertThat(McpToolClassifier.suggest(read, null)).as(read).contains(ActionType.READ);
    }
    assertThat(McpToolClassifier.suggest("place_order", null)).contains(ActionType.MONEY);
    assertThat(McpToolClassifier.suggest("modify_order", null)).contains(ActionType.MONEY);
    assertThat(McpToolClassifier.suggest("cancel_order", null)).contains(ActionType.MONEY);
    assertThat(McpToolClassifier.suggest("place_gtt_order", null)).contains(ActionType.MONEY);
    assertThat(McpToolClassifier.suggest("delete_gtt_order", null)).contains(ActionType.MONEY);
  }

  @Test
  void otherServersToo() {
    assertThat(McpToolClassifier.suggest("listIssues", null)).contains(ActionType.READ);
    assertThat(McpToolClassifier.suggest("create-page", null)).contains(ActionType.CREATE);
    assertThat(McpToolClassifier.suggest("update_record", null)).contains(ActionType.UPDATE);
    assertThat(McpToolClassifier.suggest("delete_file", null)).contains(ActionType.DELETE);
    assertThat(McpToolClassifier.suggest("send_email", null)).contains(ActionType.SEND);
    assertThat(McpToolClassifier.suggest("get_open_orders", null)).contains(ActionType.READ);
    assertThat(McpToolClassifier.suggest("get_and_delete", null)).contains(ActionType.DELETE);
  }

  @Test
  void theDescriptionDecidesWhenTheNameSaysNothing() {
    assertThat(McpToolClassifier.suggest("holdings", "Retrieve the user's holdings"))
        .contains(ActionType.READ);
    assertThat(McpToolClassifier.suggest("portfolio", "Returns the portfolio")).contains(ActionType.READ);
    assertThat(McpToolClassifier.suggest("rebalance", "Places orders to rebalance"))
        .contains(ActionType.MONEY);
    assertThat(McpToolClassifier.suggest("holdings", null)).isEmpty();
    // The name wins over its description.
    assertThat(McpToolClassifier.suggest("get_holdings", "Delete nothing, just read"))
        .contains(ActionType.READ);
  }
}
