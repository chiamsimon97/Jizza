package com.jizza.platform.usecase.vita.medicine;

import static org.assertj.core.api.Assertions.assertThat;

import com.jizza.engine.api.scheduler.RecurrenceDays;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Fixture C of spec section 10: Mother's metformin 500 mg, 1 tablet at 08:00 and 20:00, 45
 * tablets on 1 Dec 2026 before the first dose, refill lead 7 days. Doses are numbered from 1 (1
 * Dec, 08:00); day k's morning dose is number 2k − 1. Every date, dose number, stock figure and
 * percentage below is the fixture's own.
 */
class FixtureCTest {

  static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
  static final LocalDate START = LocalDate.of(2026, 12, 1);
  static final DoseExpander.Plan METFORMIN = new DoseExpander.Plan(UUID.randomUUID(), UUID.randomUUID(),
      UUID.randomUUID(), List.of(LocalTime.of(8, 0), LocalTime.of(20, 0)), "FREQ=DAILY", START, null);
  static final StockTracker.Use USE = new StockTracker.Use(METFORMIN, BigDecimal.ONE);

  /** Dose n's due time, from the expander over the whole course. */
  static List<Instant> doses(int count) {
    List<Instant> all = DoseExpander.dueTimes(METFORMIN, INDIA, START.atStartOfDay(INDIA).toInstant(),
        START.plusDays(60).atStartOfDay(INDIA).toInstant(), RecurrenceDays.DAILY);
    return all.subList(0, count);
  }

  static LocalDateTime local(Instant i) {
    return LocalDateTime.ofInstant(i, INDIA);
  }

  /** Walks the doses: those in {@code notTaken} are skipped or missed; returns the doses after which a reminder went out. */
  static List<Integer> walk(int upTo, Set<Integer> notTaken, Integer refillAfter, int pack, List<BigDecimal> stockAfter) {
    List<Instant> due = doses(upTo);
    BigDecimal stock = BigDecimal.valueOf(45);
    boolean reminded = false;
    List<Integer> reminders = new ArrayList<>();
    for (int n = 1; n <= upTo; n++) {
      if (!notTaken.contains(n)) {
        stock = StockTracker.afterTaken(stock, BigDecimal.ONE);
        LocalDate today = due.get(n - 1).atZone(INDIA).toLocalDate();
        BigDecimal needed = StockTracker.needed(List.of(USE), today, 7, RecurrenceDays.DAILY);
        if (!reminded && StockTracker.refillDue(stock, needed)) {
          reminded = true;
          reminders.add(n);
        }
      }
      if (refillAfter != null && n == refillAfter) {
        stock = StockTracker.afterRefill(stock, BigDecimal.valueOf(pack));
        reminded = false;
      }
      stockAfter.add(stock);
    }
    return reminders;
  }

  @Test
  void doseNumbersFallOnTheFixturesDates() {
    List<Instant> due = doses(61);
    assertThat(local(due.get(0))).isEqualTo(LocalDateTime.of(2026, 12, 1, 8, 0));
    assertThat(local(due.get(9))).as("dose 10").isEqualTo(LocalDateTime.of(2026, 12, 5, 20, 0));
    assertThat(local(due.get(16))).as("dose 17").isEqualTo(LocalDateTime.of(2026, 12, 9, 8, 0));
    assertThat(local(due.get(30))).as("dose 31").isEqualTo(LocalDateTime.of(2026, 12, 16, 8, 0));
    assertThat(local(due.get(32))).as("dose 33").isEqualTo(LocalDateTime.of(2026, 12, 17, 8, 0));
    assertThat(local(due.get(44))).as("dose 45").isEqualTo(LocalDateTime.of(2026, 12, 23, 8, 0));
    assertThat(local(due.get(45 - 0))).as("dose 46").isEqualTo(LocalDateTime.of(2026, 12, 23, 20, 0));
    assertThat(local(due.get(60))).as("dose 61").isEqualTo(LocalDateTime.of(2026, 12, 31, 8, 0));
    assertThat(due.get(0)).isEqualTo(Instant.parse("2026-12-01T02:30:00Z"));
  }

  @Test
  void c1EveryDoseTakenOneReminderAfterDose31AndStockStopsAtZero() {
    assertThat(StockTracker.needed(List.of(USE), LocalDate.of(2026, 12, 16), 7, RecurrenceDays.DAILY))
        .isEqualByComparingTo("14");
    List<BigDecimal> stock = new ArrayList<>();
    List<Integer> reminders = walk(46, Set.of(), null, 0, stock);
    assertThat(reminders).containsExactly(31);
    assertThat(stock.get(29)).as("after dose 30").isEqualByComparingTo("15");
    assertThat(stock.get(30)).as("after dose 31").isEqualByComparingTo("14");
    assertThat(stock.get(44)).as("after dose 45, 23 Dec 08:00").isEqualByComparingTo("0");
    assertThat(stock.get(45)).as("dose 46, 23 Dec 20:00, still due and stock stays 0").isEqualByComparingTo("0");
  }

  @Test
  void c1bARefillOf30AfterDose33GivesStock42AndTheNextReminderAtDose61() {
    List<BigDecimal> stock = new ArrayList<>();
    List<Integer> reminders = walk(61, Set.of(), 33, 30, stock);
    assertThat(StockTracker.afterRefill(BigDecimal.valueOf(12), BigDecimal.valueOf(30))).isEqualByComparingTo("42");
    assertThat(stock.get(32)).as("after dose 33 and the refill").isEqualByComparingTo("42");
    assertThat(reminders).containsExactly(31, 61);
    assertThat(stock.get(60)).as("after dose 61").isEqualByComparingTo("14");
    assertThat(local(doses(61).get(60))).isEqualTo(LocalDateTime.of(2026, 12, 31, 8, 0));
  }

  @Test
  void c2AdherenceForTheWeekOf4January2027Is86() {
    List<AdherenceCalculator.Dose> week = new ArrayList<>();
    for (int i = 0; i < 12; i++) week.add(new AdherenceCalculator.Dose("taken", false));
    week.add(new AdherenceCalculator.Dose("skipped", false));
    week.add(new AdherenceCalculator.Dose("missed", false));
    var r = AdherenceCalculator.of(week);
    assertThat(r.due()).isEqualTo(14);
    assertThat(r.taken()).isEqualTo(12);
    assertThat(r.skipped()).isEqualTo(1);
    assertThat(r.missed()).isEqualTo(1);
    assertThat(r.exact()).isEqualByComparingTo("85.71");
    assertThat(r.percent()).isEqualTo(86);
    // The week has 14 doses due: Monday 4 to Sunday 10 January, twice a day.
    var weekDoses = DoseExpander.dueTimes(METFORMIN, INDIA, LocalDate.of(2027, 1, 4).atStartOfDay(INDIA).toInstant(),
        LocalDate.of(2027, 1, 11).atStartOfDay(INDIA).toInstant(), RecurrenceDays.DAILY);
    assertThat(weekDoses).hasSize(14);
  }

  @Test
  void c3ASkippedAndAMissedDoseMoveTheReminderToDose33() {
    List<BigDecimal> stock = new ArrayList<>();
    // Dose 10 (5 Dec, 20:00) skipped and dose 17 (9 Dec, 08:00) missed.
    List<Integer> reminders = walk(33, Set.of(10, 17), null, 0, stock);
    assertThat(stock.get(30)).as("after dose 31: 45 − 29").isEqualByComparingTo("16");
    assertThat(stock.get(31)).as("after dose 32").isEqualByComparingTo("15");
    assertThat(stock.get(32)).as("after dose 33: 31 taken").isEqualByComparingTo("14");
    assertThat(reminders).containsExactly(33);
  }
}
