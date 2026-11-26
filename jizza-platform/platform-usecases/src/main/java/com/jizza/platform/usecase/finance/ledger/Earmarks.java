package com.jizza.platform.usecase.finance.ledger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The goal ledger's arithmetic for one person, account and instrument (spec 5.2): which goal
 * claims how many units. Pure; the service stores the result.
 *
 * <p>Invariant 1: the claims always add up to the units the lots still hold. Whatever no goal
 * claims belongs to the person's Unassigned goal, so the split is always whole. Units are kept to
 * six decimals; any rounding is settled by the largest remainder so no unit is lost or made.
 */
public final class Earmarks {

  private Earmarks() {}

  /** A goal's claim. */
  public record Claim(String planId, BigDecimal units) {}

  /** A change to one goal's claim, for the event log. */
  public record Delta(String planId, BigDecimal units) {}

  public record Result(List<Claim> claims, List<Delta> deltas) {}

  public static BigDecimal total(List<Claim> claims) {
    return claims.stream().map(Claim::units).reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  public static BigDecimal of(List<Claim> claims, String planId) {
    return claims.stream().filter(c -> c.planId().equals(planId)).map(Claim::units)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  /**
   * Checks a split the user typed: every goal at zero or more, at most six decimals, and the total
   * equal to what the account holds. Returns the message to show, or null when it is fine.
   */
  public static String problem(List<Claim> split, BigDecimal held) {
    for (Claim c : split) {
      if (c.units() == null || c.units().signum() < 0) return "A goal's units cannot be negative.";
      if (c.units().stripTrailingZeros().scale() > TaxLotLedger.UNITS) return "Units go to six decimals at most.";
    }
    long distinct = split.stream().map(Claim::planId).distinct().count();
    if (distinct != split.size()) return "Each goal appears once.";
    BigDecimal sum = total(split);
    if (sum.compareTo(held) != 0) {
      return "The goals add up to " + plain(sum) + " units; the account holds " + plain(held) + ".";
    }
    return null;
  }

  /** Brings the claims to {@code held}: a gap goes to (or comes off) the Unassigned goal first. */
  public static Result reconcile(List<Claim> claims, BigDecimal held, String unassignedId) {
    return reconcile(claims, held, unassignedId, TaxLotLedger.UNITS);
  }

  /** As above, rounding pro-rata cuts to {@code scale} decimals (2 for rupee amounts). */
  public static Result reconcile(List<Claim> claims, BigDecimal held, String unassignedId, int scale) {
    BigDecimal sum = total(claims);
    int cmp = sum.compareTo(held);
    if (cmp == 0) return new Result(clean(claims), List.of());
    if (cmp < 0) {
      BigDecimal gap = held.subtract(sum);
      return new Result(clean(add(claims, unassignedId, gap)), List.of(new Delta(unassignedId, gap)));
    }
    return reduce(claims, sum.subtract(held), unassignedId, scale);
  }

  /**
   * Units sold outside Kai: Unassigned gives first, then the goals pro rata by units, largest
   * remainder, so the total falls by exactly {@code by}.
   */
  public static Result reduce(List<Claim> claims, BigDecimal by, String unassignedId) {
    return reduce(claims, by, unassignedId, TaxLotLedger.UNITS);
  }

  public static Result reduce(List<Claim> claims, BigDecimal by, String unassignedId, int scale) {
    BigDecimal total = total(claims);
    if (by.compareTo(total) > 0) throw new IllegalArgumentException("Cannot take more units than are claimed");
    List<Delta> deltas = new ArrayList<>();
    BigDecimal fromUnassigned = of(claims, unassignedId).min(by);
    List<Claim> work = claims;
    if (fromUnassigned.signum() > 0) {
      work = add(work, unassignedId, fromUnassigned.negate());
      deltas.add(new Delta(unassignedId, fromUnassigned.negate()));
    }
    BigDecimal rest = by.subtract(fromUnassigned);
    if (rest.signum() > 0) {
      List<Claim> goals = work.stream().filter(c -> !c.planId().equals(unassignedId) && c.units().signum() > 0).toList();
      Map<String, BigDecimal> cut = proRata(goals, rest, scale);
      for (var e : cut.entrySet()) {
        if (e.getValue().signum() == 0) continue;
        work = add(work, e.getKey(), e.getValue().negate());
        deltas.add(new Delta(e.getKey(), e.getValue().negate()));
      }
    }
    return new Result(clean(work), deltas);
  }

  /** Splits {@code amount} over the claims in proportion to their units (largest remainder). */
  static Map<String, BigDecimal> proRata(List<Claim> claims, BigDecimal amount) {
    return proRata(claims, amount, TaxLotLedger.UNITS);
  }

  static Map<String, BigDecimal> proRata(List<Claim> claims, BigDecimal amount, int scale) {
    BigDecimal tick = BigDecimal.ONE.movePointLeft(scale);
    Map<String, BigDecimal> out = new LinkedHashMap<>();
    BigDecimal total = total(claims);
    if (total.signum() == 0) return out;
    record Share(String planId, BigDecimal floor, BigDecimal rem, BigDecimal units) {}
    List<Share> shares = new ArrayList<>();
    BigDecimal given = BigDecimal.ZERO;
    for (Claim c : claims) {
      BigDecimal exact = amount.multiply(c.units()).divide(total, 12, RoundingMode.HALF_UP);
      BigDecimal floor = exact.setScale(scale, RoundingMode.DOWN);
      shares.add(new Share(c.planId(), floor, exact.subtract(floor), c.units()));
      given = given.add(floor);
    }
    long ticks = amount.subtract(given).divide(tick, 0, RoundingMode.HALF_UP).longValue();
    List<Share> order = shares.stream().sorted(Comparator.comparing(Share::rem).reversed()
        .thenComparing(Comparator.comparing(Share::units).reversed()).thenComparing(Share::planId)).toList();
    Map<String, BigDecimal> extra = new LinkedHashMap<>();
    for (int i = 0; i < ticks && !order.isEmpty(); i++) {
      extra.merge(order.get(i % order.size()).planId(), tick, BigDecimal::add);
    }
    for (Share s : shares) out.put(s.planId(), s.floor().add(extra.getOrDefault(s.planId(), BigDecimal.ZERO)));
    return out;
  }

  /** A goal sells {@code qty} of its own units (a sale Kai attributes to it). */
  public static Result sellFrom(List<Claim> claims, String planId, BigDecimal qty) {
    BigDecimal has = of(claims, planId);
    if (has.compareTo(qty) < 0) {
      throw new IllegalArgumentException("That goal has " + plain(has) + " units, fewer than " + plain(qty));
    }
    return new Result(clean(add(claims, planId, qty.negate())), List.of(new Delta(planId, qty.negate())));
  }

  /** New units bought for a goal (or for Unassigned when no goal funded them). */
  public static Result buyFor(List<Claim> claims, String planId, BigDecimal qty) {
    return new Result(clean(add(claims, planId, qty)), List.of(new Delta(planId, qty)));
  }

  /**
   * Moves units from one goal to another. Done only with a cash leg (spec 5.2): the receiving goal
   * pays the giving goal from its cash in the same account; the service checks the cash.
   */
  public static Result reassign(List<Claim> claims, String fromPlan, String toPlan, BigDecimal qty) {
    if (fromPlan.equals(toPlan)) throw new IllegalArgumentException("Choose two different goals");
    BigDecimal has = of(claims, fromPlan);
    if (has.compareTo(qty) < 0) {
      throw new IllegalArgumentException("That goal has " + plain(has) + " units, fewer than " + plain(qty));
    }
    List<Claim> work = add(add(claims, fromPlan, qty.negate()), toPlan, qty);
    return new Result(clean(work), List.of(new Delta(fromPlan, qty.negate()), new Delta(toPlan, qty)));
  }

  /**
   * A split or bonus multiplies every claim by the same ratio; the new total is what the lots now
   * hold ({@code newTotal}), and rounding goes by the largest remainder.
   */
  public static Result scale(List<Claim> claims, BigDecimal newTotal) {
    List<Claim> live = claims.stream().filter(c -> c.units().signum() > 0).toList();
    Map<String, BigDecimal> scaled = proRata(live, newTotal);
    List<Claim> out = new ArrayList<>();
    List<Delta> deltas = new ArrayList<>();
    for (Claim c : live) {
      BigDecimal n = scaled.getOrDefault(c.planId(), BigDecimal.ZERO);
      if (n.signum() > 0) out.add(new Claim(c.planId(), n));
      if (n.compareTo(c.units()) != 0) deltas.add(new Delta(c.planId(), n.subtract(c.units())));
    }
    return new Result(out, deltas);
  }

  /** The split of a dividend (or any amount) by units on the record date, to the paisa. */
  public static Map<String, BigDecimal> shareOfAmount(List<Claim> claims, BigDecimal amount) {
    return proRata(claims.stream().filter(c -> c.units().signum() > 0).toList(), amount, TaxLotLedger.MONEY);
  }

  static List<Claim> add(List<Claim> claims, String planId, BigDecimal delta) {
    List<Claim> out = new ArrayList<>();
    boolean found = false;
    for (Claim c : claims) {
      if (c.planId().equals(planId)) {
        out.add(new Claim(planId, c.units().add(delta)));
        found = true;
      } else {
        out.add(c);
      }
    }
    if (!found) out.add(new Claim(planId, delta));
    return out;
  }

  /** Drops zero claims and merges duplicates; keeps first-seen order. */
  public static List<Claim> clean(List<Claim> claims) {
    Map<String, BigDecimal> m = new LinkedHashMap<>();
    for (Claim c : claims) m.merge(c.planId(), c.units(), BigDecimal::add);
    List<Claim> out = new ArrayList<>();
    m.forEach((k, v) -> {
      if (v.signum() < 0) throw new IllegalStateException("A claim went below zero");
      if (v.signum() > 0) out.add(new Claim(k, v.setScale(TaxLotLedger.UNITS, RoundingMode.UNNECESSARY)));
    });
    return out;
  }

  static String plain(BigDecimal v) {
    BigDecimal s = Objects.requireNonNull(v).stripTrailingZeros();
    return s.scale() < 0 ? s.setScale(0).toPlainString() : s.toPlainString();
  }
}
