package com.jizza.platform.usecase.finance.ledger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The FIFO tax ledger (spec 5.2): every sale consumes the oldest lot in that account first
 * (CBDT Circular 768, Section 45(2A)), whichever goal sold. A pure function of its input.
 *
 * <p>A lot whose purchase date is not known (an opening balance read from a holdings file) is
 * taken as older than every dated lot, because it was already held when the history starts; its
 * gain has no term until the user gives the date, and it is flagged.
 */
public final class TaxLotLedger {

  private TaxLotLedger() {}

  public static final int UNITS = 6;
  public static final int MONEY = 2;

  /** One lot: {@code remaining} of {@code qty} still held, bought at {@code costPerUnit} (null if unknown). */
  public record Lot(String id, LocalDate acquiredOn, boolean dateKnown, BigDecimal qty, BigDecimal remaining,
                    BigDecimal costPerUnit) {

    public Lot withRemaining(BigDecimal r) {
      return new Lot(id, acquiredOn, dateKnown, qty, r, costPerUnit);
    }
  }

  /**
   * How long a lot must be held to be long term. {@code alwaysShort} for gains taxed at slab
   * whatever the period; {@code shortTermMonths} null when the period is not configured (the term
   * is then left open).
   */
  public record TermRule(Integer shortTermMonths, boolean alwaysShort) {
    public static TermRule months(int m) { return new TermRule(m, false); }
    public static TermRule unknown() { return new TermRule(null, false); }
    public static TermRule slab() { return new TermRule(null, true); }
  }

  /** What one lot gave to a sale. {@code term} is st, lt, or null when it cannot be told. */
  public record Part(String lotId, BigDecimal qty, BigDecimal proceeds, BigDecimal cost, BigDecimal gain,
                     String term, LocalDate acquiredOn) {}

  /** A sale: the parts in FIFO order and every lot afterwards. */
  public record Sale(List<Part> parts, List<Lot> after, BigDecimal proceeds, BigDecimal cost, BigDecimal gain) {}

  /** Lots in sale order: undated first, then by date, then by id. */
  public static List<Lot> fifo(List<Lot> lots) {
    return lots.stream().sorted(Comparator
        .comparing((Lot l) -> l.dateKnown() ? 1 : 0)
        .thenComparing(l -> l.acquiredOn() == null ? LocalDate.MIN : l.acquiredOn())
        .thenComparing(Lot::id)).toList();
  }

  public static BigDecimal remaining(List<Lot> lots) {
    return lots.stream().map(Lot::remaining).reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  /**
   * Sells {@code qty} units for {@code totalProceeds} in all (split over the lots by units; the
   * last part takes the rounding). {@code totalProceeds} may be null when the price is not known:
   * the lots are still consumed, and proceeds and gains stay null.
   *
   * @throws IllegalArgumentException when the lots hold fewer units than are sold
   */
  public static Sale sell(List<Lot> lots, BigDecimal qty, BigDecimal totalProceeds, LocalDate soldOn,
                          TermRule rule) {
    return sell(lots, qty, totalProceeds, soldOn, l -> rule);
  }

  /** As above, with the holding-period rule chosen per lot (a debt fund's depends on its date). */
  public static Sale sell(List<Lot> lots, BigDecimal qty, BigDecimal totalProceeds, LocalDate soldOn,
                          java.util.function.Function<Lot, TermRule> rules) {
    if (qty == null || qty.signum() <= 0) throw new IllegalArgumentException("Nothing to sell");
    BigDecimal held = remaining(lots);
    if (held.compareTo(qty) < 0) {
      throw new IllegalArgumentException("The account holds " + held.stripTrailingZeros().toPlainString()
          + " units, fewer than the " + qty.stripTrailingZeros().toPlainString() + " sold");
    }
    List<Lot> ordered = fifo(lots);
    List<Lot> after = new ArrayList<>();
    List<Part> parts = new ArrayList<>();
    BigDecimal left = qty;
    BigDecimal proceedsLeft = totalProceeds;
    for (Lot lot : ordered) {
      if (left.signum() == 0 || lot.remaining().signum() == 0) {
        after.add(lot);
        continue;
      }
      BigDecimal take = lot.remaining().min(left);
      left = left.subtract(take);
      BigDecimal proceeds = null;
      if (totalProceeds != null) {
        proceeds = left.signum() == 0 ? proceedsLeft
            : totalProceeds.multiply(take).divide(qty, MONEY, RoundingMode.HALF_UP);
        proceedsLeft = proceedsLeft.subtract(proceeds);
      }
      BigDecimal cost = lot.costPerUnit() == null ? null
          : lot.costPerUnit().multiply(take).setScale(MONEY, RoundingMode.HALF_UP);
      BigDecimal gain = proceeds == null || cost == null ? null : proceeds.subtract(cost);
      parts.add(new Part(lot.id(), take, proceeds, cost, gain, term(lot, soldOn, rules.apply(lot)), lot.acquiredOn()));
      after.add(lot.withRemaining(lot.remaining().subtract(take)));
    }
    return new Sale(parts, after, sum(parts, Part::proceeds), sum(parts, Part::cost), sum(parts, Part::gain));
  }

  /** st or lt on the sale date, or null when the date or the rule is not known. */
  public static String term(Lot lot, LocalDate soldOn, TermRule rule) {
    if (rule.alwaysShort()) return "st";
    if (!lot.dateKnown() || lot.acquiredOn() == null || rule.shortTermMonths() == null) return null;
    return soldOn.isAfter(lot.acquiredOn().plusMonths(rule.shortTermMonths())) ? "lt" : "st";
  }

  /** The financial year of a date: 1 April to 31 March, as "2026-27". */
  public static String taxYear(LocalDate d) {
    int start = d.getMonthValue() >= 4 ? d.getYear() : d.getYear() - 1;
    return start + "-" + String.format("%02d", (start + 1) % 100);
  }

  /** Weighted average cost per unit of what is still held; null when a lot's cost is unknown. */
  public static BigDecimal averageCost(List<Lot> lots) {
    BigDecimal units = BigDecimal.ZERO;
    BigDecimal cost = BigDecimal.ZERO;
    for (Lot l : lots) {
      if (l.remaining().signum() == 0) continue;
      if (l.costPerUnit() == null) return null;
      units = units.add(l.remaining());
      cost = cost.add(l.costPerUnit().multiply(l.remaining()));
    }
    return units.signum() == 0 ? null : cost.divide(units, 4, RoundingMode.HALF_UP);
  }

  private static BigDecimal sum(List<Part> parts, java.util.function.Function<Part, BigDecimal> f) {
    BigDecimal total = BigDecimal.ZERO;
    for (Part p : parts) {
      BigDecimal v = f.apply(p);
      if (v == null) return null;
      total = total.add(v);
    }
    return total;
  }
}
