package com.jizza.platform.usecase.vita.medicine;

import static org.assertj.core.api.Assertions.assertThat;

import com.jizza.engine.api.scheduler.RecurrenceDays;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DoseExpanderTest {

  static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

  @Test
  void expandingTwiceGivesTheSameTimesAndAClosedScheduleStopsOnItsLastDay() {
    var plan = new DoseExpander.Plan(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
        List.of(LocalTime.of(20, 0), LocalTime.of(8, 0)), "FREQ=DAILY", LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 2));
    Instant from = Instant.parse("2026-11-30T00:00:00Z");
    Instant to = from.plus(DoseExpander.AHEAD.multipliedBy(3));
    var a = DoseExpander.dueTimes(plan, INDIA, from, to, RecurrenceDays.DAILY);
    assertThat(a).isEqualTo(DoseExpander.dueTimes(plan, INDIA, from, to, RecurrenceDays.DAILY));
    assertThat(a).hasSize(4).isSorted();
  }

  @Test
  void anEmptyWindowGivesNothingAndAWeeklyRuleOnlyItsDays() {
    var plan = new DoseExpander.Plan(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
        List.of(LocalTime.of(9, 0)), "FREQ=WEEKLY;BYDAY=MO", LocalDate.of(2026, 12, 7), null);
    RecurrenceDays mondays = new RecurrenceDays() {
      @Override public boolean occursOn(String rrule, LocalDate firstDay, LocalDate day) {
        return !day.isBefore(firstDay) && day.getDayOfWeek() == java.time.DayOfWeek.MONDAY;
      }
      @Override public void validate(String rrule) {}
    };
    Instant from = LocalDate.of(2026, 12, 7).atStartOfDay(INDIA).toInstant();
    assertThat(DoseExpander.dueTimes(plan, INDIA, from, from, mondays)).isEmpty();
    var due = DoseExpander.dueTimes(plan, INDIA, from, from.plus(java.time.Duration.ofDays(14)), mondays);
    assertThat(due).hasSize(2);
    assertThat(StockTracker.needed(List.of(new StockTracker.Use(plan, BigDecimal.valueOf(2))),
        LocalDate.of(2026, 12, 7), 7, mondays)).isEqualByComparingTo("2");
  }

  @Test
  void stockNeverGoesBelowZero() {
    assertThat(StockTracker.afterTaken(BigDecimal.valueOf(0.5), BigDecimal.ONE)).isEqualByComparingTo("0");
    assertThat(StockTracker.refillDue(BigDecimal.TEN, BigDecimal.ZERO)).as("nothing used, no refill").isFalse();
    assertThat(AdherenceCalculator.of(List.of()).percent()).isNull();
  }
}
