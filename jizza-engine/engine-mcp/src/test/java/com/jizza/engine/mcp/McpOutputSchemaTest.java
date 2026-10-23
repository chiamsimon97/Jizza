package com.jizza.engine.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.jizza.engine.api.util.Json;
import org.junit.jupiter.api.Test;

/** What a tool returned, kept as its shape so later workflow steps can map its fields. */
class McpOutputSchemaTest {

  @Test
  void aListOfHoldingsBecomesTheShapeOfOneHolding() {
    var schema = McpOutputSchema.infer(Json.read("""
        [{"tradingsymbol":"INFY","quantity":10,"last_price":1500.5,"mtf":{"value":0}},
         {"tradingsymbol":"TCS","quantity":2,"last_price":3900,"t1_quantity":1}]"""));

    assertThat(schema.path("type").asText()).isEqualTo("array");
    var item = schema.path("items").path("properties");
    assertThat(item.path("tradingsymbol").path("type").asText()).isEqualTo("string");
    assertThat(item.path("quantity").path("type").asText()).isEqualTo("integer");
    // 1500.5 and 3900 together are numbers; a field only the second item has is kept too.
    assertThat(item.path("last_price").path("type").asText()).isEqualTo("number");
    assertThat(item.has("t1_quantity")).isTrue();
    assertThat(item.path("mtf").path("properties").path("value").path("type").asText())
        .isEqualTo("integer");
  }

  @Test
  void onlyTheShapeIsKeptNeverTheValues() {
    var schema = McpOutputSchema.infer(Json.read("{\"name\":\"Priya\",\"pan\":\"ABCDE1234F\"}"));
    assertThat(schema.toString()).doesNotContain("Priya", "ABCDE1234F");
  }
}
