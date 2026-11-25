package com.jizza.engine.infra.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcFinanceRecordsTest {

  @Test
  void namesMapBetweenCamelAndSnakeCase() {
    assertThat(JdbcFinanceRecords.snake("effectiveAnnualRate")).isEqualTo("effective_annual_rate");
    assertThat(JdbcFinanceRecords.camel("take_home_monthly")).isEqualTo("takeHomeMonthly");
    assertThat(JdbcFinanceRecords.camel("last4")).isEqualTo("last4");
  }

  @Test
  void valuesBecomeTextForTheirCastAndArraysBecomeArrayLiterals() {
    var f = JsonNodeFactory.instance;
    assertThat(JdbcFinanceRecords.param(f.numberNode(new BigDecimal("1E+5")))).isEqualTo("100000");
    assertThat(JdbcFinanceRecords.param(f.booleanNode(true))).isEqualTo("true");
    assertThat(JdbcFinanceRecords.param(f.nullNode())).isNull();
    var arr = f.arrayNode().add("a\"b").add("c");
    assertThat(JdbcFinanceRecords.param(arr)).isEqualTo("{\"a\\\"b\",\"c\"}");
    var obj = f.objectNode().put("k", 1);
    assertThat(JdbcFinanceRecords.param(obj)).isEqualTo("{\"k\":1}");
  }

  @Test
  void rowsReadBackWithExactDecimalsAndCamelCaseNames() {
    var row = JdbcFinanceRecords.toRow("{\"take_home_monthly\": 120000.00, \"effective_annual_rate\": 14.000000, \"checks\": [{\"a\": 1}]}");
    assertThat(row.path("takeHomeMonthly").decimalValue()).isEqualByComparingTo("120000");
    assertThat(row.path("takeHomeMonthly").toString()).isEqualTo("120000");
    assertThat(row.path("effectiveAnnualRate").toString()).isEqualTo("14");
    assertThat(row.path("checks").get(0).path("a").asInt()).isEqualTo(1);
  }

  @Test
  void onlyTheFinanceTablesAreServed() {
    var records = new JdbcFinanceRecords(mock(JdbcTemplate.class));
    assertThatThrownBy(() -> records.list("users", UUID.randomUUID(), Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> records.delete("fin_config", UUID.randomUUID(), UUID.randomUUID()))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
