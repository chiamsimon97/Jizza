package com.jizza.engine.connectors.finance;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class AmfiPriceFeedTest {

  @Test
  void readsSchemeLinesAndSkipsTheRest() {
    String body = """
        Scheme Code;ISIN Div Payout/ ISIN Growth;ISIN Div Reinvestment;Scheme Name;Net Asset Value;Date

        Open Ended Schemes(Debt Scheme - Banking and PSU Fund)

        Example Mutual Fund
        100001;INF000A01AB1;-;Example Liquid Fund - Growth;1234.5678;25-Nov-2026
        100002;INF000A01AC9;INF000A01AD7;Example Index Fund - IDCW;N.A.;25-Nov-2026
        100003;INF000A01AE5;;Example Flexi Cap - Growth;56.78;24-Nov-2026
        """;
    var navs = AmfiPriceFeed.parse(body);
    assertThat(navs).hasSize(2);
    assertThat(navs.get(0).amfiCode()).isEqualTo("100001");
    assertThat(navs.get(0).isinGrowth()).isEqualTo("INF000A01AB1");
    assertThat(navs.get(0).isinReinvest()).isNull();
    assertThat(navs.get(0).nav()).isEqualByComparingTo(new BigDecimal("1234.5678"));
    assertThat(navs.get(1).date()).isEqualTo(LocalDate.of(2026, 11, 24));
  }
}
