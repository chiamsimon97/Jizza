package com.jizza.platform.usecase.vita.insurance;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;

/**
 * A health policy's year (spec 4.3): the 12 months ending on its renewal date. "Used this policy
 * year" is the paid amount of settled claims whose stay began in that year; "left" is the sum
 * insured less what was used, never below zero. A policy with no renewal date has no year, so
 * neither figure is shown for it.
 */
public record PolicyYear(LocalDate from, LocalDate to) {

  /** The kinds of {@code fin_insurance_policy} Vita owns (spec 4.3). */
  public static final Set<String> HEALTH_KINDS =
      Set.of("health", "super_topup", "critical_illness", "personal_accident", "government_scheme");

  public static boolean healthKind(String kind) {
    return kind != null && HEALTH_KINDS.contains(kind);
  }

  /** The year ending on {@code renewal}, or null when there is no renewal date. */
  public static PolicyYear endingOn(LocalDate renewal) {
    return renewal == null ? null : new PolicyYear(renewal.minusYears(1).plusDays(1), renewal);
  }

  /** The policy year, on the same renewal day of the year, that a day falls in. */
  public static PolicyYear containing(LocalDate renewal, LocalDate day) {
    PolicyYear y = endingOn(renewal);
    int guard = 0;
    while (day.isAfter(y.to()) && guard++ < 200) y = endingOn(renewal.plusYears(guard));
    while (day.isBefore(y.from()) && guard++ < 400) y = endingOn(y.to().minusYears(1));
    return y;
  }

  public boolean contains(LocalDate day) {
    return day != null && !day.isBefore(from) && !day.isAfter(to);
  }

  /** What is left of the cover after {@code used}; zero once it is all used. */
  public static BigDecimal left(BigDecimal sumInsured, BigDecimal used) {
    BigDecimal s = sumInsured == null ? BigDecimal.ZERO : sumInsured;
    BigDecimal u = used == null ? BigDecimal.ZERO : used;
    return s.subtract(u).max(BigDecimal.ZERO);
  }

  /**
   * What the family paid themselves on one claim: claimed less paid once settled, everything
   * claimed once rejected, and nothing known while the claim is still open.
   */
  public static BigDecimal outOfPocket(String status, BigDecimal claimed, BigDecimal paid) {
    if (claimed == null) return null;
    return switch (status == null ? "" : status) {
      case "settled" -> claimed.subtract(paid == null ? BigDecimal.ZERO : paid).max(BigDecimal.ZERO);
      case "rejected" -> claimed;
      default -> null;
    };
  }
}
