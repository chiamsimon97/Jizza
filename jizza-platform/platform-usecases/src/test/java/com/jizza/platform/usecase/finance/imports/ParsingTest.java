package com.jizza.platform.usecase.finance.imports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class ParsingTest {

  @Test
  void readsDayFirstDatesInTheUsualLayouts() {
    LocalDate d = LocalDate.of(2025, 1, 5);
    for (String s : new String[] {"05/01/2025", "5/1/2025", "05-01-25", "05-Jan-2025", "05 JAN 2025",
        "5 January 2025", "2025-01-05", "05.01.2025", "05/01/2025 10:22:11", "05-Jan-25", "2025-01-05T09:00:00"}) {
      assertThat(Parsing.date(s)).as(s).isEqualTo(d);
    }
    assertThat(Parsing.date("31/02/2025")).isNull();
    assertThat(Parsing.date("Opening balance")).isNull();
    assertThat(Parsing.date("")).isNull();
  }

  @Test
  void readsAmountsWithIndianCommasSignsAndDrCr() {
    assertThat(Parsing.amount("1,23,456.78").value()).isEqualByComparingTo("123456.78");
    assertThat(Parsing.amount("₹ 500").value()).isEqualByComparingTo("500");
    assertThat(Parsing.amount("(250.00)").value()).isEqualByComparingTo("-250");
    assertThat(Parsing.amount("-12").value()).isEqualByComparingTo("-12");
    assertThat(Parsing.amount("8,000.00 Cr").drCr()).isEqualTo("cr");
    assertThat(Parsing.amount("100 Dr.").drCr()).isEqualTo("dr");
    assertThat(Parsing.amount("  ")).isNull();
    assertThat(Parsing.amount("-")).isNull();
    assertThatThrownBy(() -> Parsing.amount("abc")).isInstanceOf(NumberFormatException.class);
  }
}
