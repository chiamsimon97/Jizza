package com.jizza.platform.usecase.vita.ranges;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * One red-flag rule for readings, read from {@code clinical.redflags.<id>} (spec 8.2). The numbers
 * are the config's; this class only compares. A BP rule fires when the systolic is above its bound
 * or the diastolic above its own ("above" excludes the bound); a low-sugar rule when the value is
 * below its bound.
 */
public record RedFlagRule(String id, String kind, BigDecimal systolicAbove, BigDecimal diastolicAbove,
                          BigDecimal below, BigDecimal veryLowBelow, String level, String raisedTo,
                          String question, List<String> signs, String advice, String emergencyAdvice,
                          boolean needsVerification) {

  public static final String URGENT = "urgent";
  public static final String EMERGENCY = "emergency";

  /** The rule from its config value; null when the value names no reading kind. */
  public static RedFlagRule fromConfig(String id, JsonNode v, boolean needsVerification) {
    if (v == null || !v.hasNonNull("kind")) return null;
    List<String> signs = new ArrayList<>();
    v.path("signs").forEach(s -> signs.add(s.asText()));
    return new RedFlagRule(id, v.get("kind").asText(), dec(v, "systolicAbove"), dec(v, "diastolicAbove"),
        dec(v, "below"), dec(v, "veryLowBelow"), v.path("level").asText(URGENT),
        v.path("raisedTo").asText(EMERGENCY), text(v, "question"), List.copyOf(signs),
        text(v, "advice"), text(v, "emergencyAdvice"), needsVerification);
  }

  /** Whether the reading matches; false for another kind. */
  public boolean matches(String kind, BigDecimal value, BigDecimal value2) {
    if (!this.kind.equals(kind) || value == null) return false;
    if ("bp".equals(kind)) {
      boolean sys = systolicAbove != null && value.compareTo(systolicAbove) > 0;
      boolean dia = diastolicAbove != null && value2 != null && value2.compareTo(diastolicAbove) > 0;
      return sys || dia;
    }
    return below != null && value.compareTo(below) < 0;
  }

  /** For low sugar: below the second, lower bound. */
  public boolean veryLow(BigDecimal value) {
    return veryLowBelow != null && value != null && value.compareTo(veryLowBelow) < 0;
  }

  private static BigDecimal dec(JsonNode v, String f) {
    return v.hasNonNull(f) && v.get(f).isNumber() ? v.get(f).decimalValue() : null;
  }

  private static String text(JsonNode v, String f) {
    return v.hasNonNull(f) ? v.get(f).asText() : null;
  }
}
