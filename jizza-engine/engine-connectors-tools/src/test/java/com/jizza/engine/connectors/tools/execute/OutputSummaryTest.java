package com.jizza.engine.connectors.tools.execute;

import static org.assertj.core.api.Assertions.assertThat;

import com.jizza.engine.api.util.Json;
import org.junit.jupiter.api.Test;

class OutputSummaryTest {

  @Test
  void keepsAFewReadableValuesAndNothingThatLooksLikeACredential() throws Exception {
    var body = Json.mapper().readTree("""
        {"balance": 12400.5, "currency": "INR", "access_token": "abc", "apiKey": "k",
         "account": {"type": "demat", "sessionId": "s", "deep": {"tooFar": 1}},
         "holdings": [{"symbol": "INFY"}, {"symbol": "TCS"}]}""");

    var summary = OutputSummary.of(body);

    assertThat(summary.path("fields").toString())
        .contains("balance", "12400.5", "INR", "account.type", "demat", "2 items")
        .doesNotContain("abc", "access_token", "apiKey", "sessionId", "tooFar");
    assertThat(OutputSummary.line("Zerodha", "get holdings", summary))
        .startsWith("Zerodha get holdings: balance=12400.5;");
  }

  @Test
  void aListReportsItsSizeAndTheShapeOfItsFirstItem() throws Exception {
    var summary = OutputSummary.of(Json.mapper().readTree("[{\"name\":\"Rex\"},{\"name\":\"Tom\"}]"));
    assertThat(summary.path("items").asInt()).isEqualTo(2);
    assertThat(summary.path("fields").get(0).path("value").asText()).isEqualTo("Rex");
  }

  @Test
  void neverMoreThanAHandfulOfFields() throws Exception {
    var many = Json.obj();
    for (int i = 0; i < 30; i++) many.put("f" + i, i);
    assertThat(OutputSummary.of(many).path("fields")).hasSize(OutputSummary.MAX_FIELDS);
  }
}
