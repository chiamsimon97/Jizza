package com.jizza.platform.usecase.vita.ranges;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * One bounded range for one number (spec 2.4). A range written "a–b" includes both ends; "below x"
 * excludes x; "x or lower" includes it. Either bound may be missing. Pure value, no figures of its
 * own: every range comes from config, the person's own row or a doctor's target.
 */
public record Range(BigDecimal low, boolean lowInclusive, BigDecimal high, boolean highInclusive) {

  public Range {
    if (low == null && high == null) throw new IllegalArgumentException("A range needs a bound");
  }

  public static Range below(BigDecimal x) { return new Range(null, true, x, false); }
  public static Range atMost(BigDecimal x) { return new Range(null, true, x, true); }
  public static Range between(BigDecimal lo, BigDecimal hi) { return new Range(lo, true, hi, true); }

  /** Where the value falls: null inside, {@code "low"} under the range, {@code "high"} over it. */
  public String side(BigDecimal v) {
    if (low != null) {
      int c = v.compareTo(low);
      if (c < 0 || (c == 0 && !lowInclusive)) return "low";
    }
    if (high != null) {
      int c = v.compareTo(high);
      if (c > 0 || (c == 0 && !highInclusive)) return "high";
    }
    return null;
  }

  public boolean contains(BigDecimal v) {
    return side(v) == null;
  }

  /** The range in words: "below 140", "80–130", "7.0 or lower", "above 60", "60 or higher". */
  public String text() {
    if (low != null && high != null) {
      if (lowInclusive && highInclusive) return plain(low) + "–" + plain(high);
      return (lowInclusive ? "from " : "above ") + plain(low) + (highInclusive ? " to " : " and below ") + plain(high);
    }
    if (high != null) return highInclusive ? plain(high) + " or lower" : "below " + plain(high);
    return lowInclusive ? plain(low) + " or higher" : "above " + plain(low);
  }

  static String plain(BigDecimal d) {
    BigDecimal s = d.stripTrailingZeros();
    if (s.scale() < 0) s = s.setScale(0, RoundingMode.UNNECESSARY);
    // "7.0 or lower" keeps the decimal the figure was written with.
    if (d.scale() > 0 && s.scale() == 0 && d.scale() <= 1) return d.setScale(1, RoundingMode.UNNECESSARY).toPlainString();
    return s.toPlainString();
  }

  /**
   * Reads a range as config writes it: {@code {"below": 140}}, {@code {"atMost": 7.0}},
   * {@code {"above": x}}, {@code {"atLeast": x}}, or {@code {"low", "high", "lowInclusive",
   * "highInclusive"}}. Null when the node holds no bound.
   */
  public static Range fromConfig(JsonNode n) {
    if (n == null || !n.isObject()) return null;
    BigDecimal lo = null;
    BigDecimal hi = null;
    boolean loInc = true;
    boolean hiInc = true;
    if (n.hasNonNull("below")) { hi = n.get("below").decimalValue(); hiInc = false; }
    if (n.hasNonNull("atMost")) { hi = n.get("atMost").decimalValue(); hiInc = true; }
    if (n.hasNonNull("above")) { lo = n.get("above").decimalValue(); loInc = false; }
    if (n.hasNonNull("atLeast")) { lo = n.get("atLeast").decimalValue(); loInc = true; }
    if (n.hasNonNull("low")) { lo = n.get("low").decimalValue(); loInc = n.path("lowInclusive").asBoolean(true); }
    if (n.hasNonNull("high")) { hi = n.get("high").decimalValue(); hiInc = n.path("highInclusive").asBoolean(true); }
    return lo == null && hi == null ? null : new Range(lo, loInc, hi, hiInc);
  }
}
