package com.jizza.engine.scheduler.recurrence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class RruleRecurrenceDaysTest {

  private final RruleRecurrenceDays days = new RruleRecurrenceDays();
  private final LocalDate first = LocalDate.of(2026, 12, 1); // a Tuesday

  @Test
  void dailyRulesFallOnEveryDayFromTheFirst() {
    assertThat(days.occursOn("FREQ=DAILY", first, first)).isTrue();
    assertThat(days.occursOn("FREQ=DAILY", first, first.plusDays(40))).isTrue();
    assertThat(days.occursOn("FREQ=DAILY", first, first.minusDays(1))).isFalse();
    assertThat(days.occursOn("FREQ=DAILY;INTERVAL=2", first, first.plusDays(1))).isFalse();
    assertThat(days.occursOn("FREQ=DAILY;INTERVAL=2", first, first.plusDays(2))).isTrue();
  }

  @Test
  void weeklyRulesFallOnTheirDaysOnly() {
    String rule = "FREQ=WEEKLY;BYDAY=MO,TH";
    assertThat(days.occursOn(rule, first, LocalDate.of(2026, 12, 3))).as("Thursday").isTrue();
    assertThat(days.occursOn(rule, first, LocalDate.of(2026, 12, 7))).as("Monday").isTrue();
    assertThat(days.occursOn(rule, first, LocalDate.of(2026, 12, 8))).as("Tuesday").isFalse();
  }

  @Test
  void aRuleItCannotReadIsRefused() {
    assertThatThrownBy(() -> days.validate("every day")).isInstanceOf(RuntimeException.class);
    assertThatThrownBy(() -> days.validate("FREQ=SOMETIMES")).isInstanceOf(RuntimeException.class);
    days.validate("FREQ=WEEKLY;BYDAY=MO");
  }
}
