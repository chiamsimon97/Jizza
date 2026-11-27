package com.jizza.platform.usecase.vita.medicine;

import com.jizza.engine.api.scheduler.RecurrenceDays;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;

/**
 * A medicine's stock (spec 5.2): each taken dose lowers it by the dose, never below 0; a refill
 * adds a pack. A refill is due when the stock covers the refill lead days of doses or fewer. Pure.
 */
public final class StockTracker {

  private StockTracker() {}

  /** One open schedule's use: how many units each time, how many times a day, on which days. */
  public record Use(DoseExpander.Plan plan, BigDecimal unitsPerDose) {}

  public static BigDecimal afterTaken(BigDecimal stock, BigDecimal dose) {
    if (stock == null) return null;
    BigDecimal left = stock.subtract(dose == null ? BigDecimal.ZERO : dose);
    return left.signum() < 0 ? BigDecimal.ZERO : left;
  }

  public static BigDecimal afterRefill(BigDecimal stock, BigDecimal units) {
    return (stock == null ? BigDecimal.ZERO : stock).add(units);
  }

  /** The units the open schedules use over {@code leadDays} days from {@code today}. */
  public static BigDecimal needed(Collection<Use> uses, LocalDate today, int leadDays, RecurrenceDays days) {
    BigDecimal total = BigDecimal.ZERO;
    for (Use u : uses) {
      var p = u.plan();
      String rule = p.recurrence() == null || p.recurrence().isBlank() ? "FREQ=DAILY" : p.recurrence();
      for (int i = 0; i < leadDays; i++) {
        LocalDate d = today.plusDays(i);
        if (d.isBefore(p.from()) || (p.to() != null && d.isAfter(p.to()))) continue;
        if (!days.occursOn(rule, p.from(), d)) continue;
        total = total.add(u.unitsPerDose().multiply(BigDecimal.valueOf(p.times().size())));
      }
    }
    return total;
  }

  /** Whether the stock covers the lead days of doses or fewer (and something is used at all). */
  public static boolean refillDue(BigDecimal stock, BigDecimal needed) {
    return stock != null && needed != null && needed.signum() > 0 && stock.compareTo(needed) <= 0;
  }
}
