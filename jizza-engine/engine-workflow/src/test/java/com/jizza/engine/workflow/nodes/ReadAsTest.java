package com.jizza.engine.workflow.nodes;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.util.Json;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class ReadAsTest {

  private static ObjectNode read(String text, String rule) {
    ObjectNode out = Json.obj().put("text", text);
    ReadAs.apply(Json.read(rule), out);
    return out;
  }

  @Test
  void readsNumbersWrittenEachWay() {
    assertThat(ReadAs.number("₹2,345.00")).isEqualByComparingTo(new BigDecimal("2345.00"));
    assertThat(ReadAs.number("Total: 1.234,50 €")).isEqualByComparingTo(new BigDecimal("1234.50"));
    assertThat(ReadAs.number("Rs. 1,23,456")).isEqualByComparingTo(new BigDecimal("123456"));
    assertThat(ReadAs.number("12,5 kg")).isEqualByComparingTo(new BigDecimal("12.5"));
    assertThat(ReadAs.number("12 345 items")).isEqualByComparingTo(new BigDecimal("12345"));
    assertThat(ReadAs.number("-3.5")).isEqualByComparingTo(new BigDecimal("-3.5"));
  }

  @Test
  void moneyKeepsItsCurrency() {
    var out = read("Fare ₹2,345.00", "{\"as\":\"money\"}");
    assertThat(out.path("value").decimalValue()).isEqualByComparingTo(new BigDecimal("2345.00"));
    assertThat(out.path("currency").asText()).isEqualTo("₹");
    assertThat(out.path("text").asText()).isEqualTo("Fare ₹2,345.00");
  }

  @Test
  void readsDatesInsideOtherWords() {
    assertThat(ReadAs.date("Journey on 20-Dec-2025 (Sat)", "")).isEqualTo(LocalDate.of(2025, 12, 20));
    assertThat(ReadAs.date("Departs Sat, 20 Dec 2025", "")).isEqualTo(LocalDate.of(2025, 12, 20));
    assertThat(ReadAs.date("December 20th, 2025", "")).isEqualTo(LocalDate.of(2025, 12, 20));
    assertThat(ReadAs.date("20/12/2025", "dd/MM/yyyy")).isEqualTo(LocalDate.of(2025, 12, 20));
    assertThat(read("12/20/2025", "{\"as\":\"date\",\"format\":\"MM/dd/yyyy\"}").path("value").asText())
        .isEqualTo("2025-12-20");
  }

  @Test
  void aPatternGivesItsFirstGroup() {
    assertThat(read("PNR: 4512345678 | Coach B2", "{\"as\":\"pattern\",\"pattern\":\"PNR:\\\\s*(\\\\d{10})\"}")
        .path("value").asText()).isEqualTo("4512345678");
    assertThat(ReadAs.pattern("Order #AB-123", "[A-Z]{2}-\\d+")).isEqualTo("AB-123");
  }

  @Test
  void aTextThatDoesNotFitSaysWhyAndKeepsTheText() {
    var out = read("Sold out", "{\"as\":\"number\"}");
    assertThat(out.has("value")).isFalse();
    assertThat(out.path("valueProblem").asText()).contains("No number");
    assertThat(out.path("text").asText()).isEqualTo("Sold out");
    assertThat(read("Sold out", "{\"as\":\"text\"}").has("value")).isFalse();
  }
}
