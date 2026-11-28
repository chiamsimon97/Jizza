package com.jizza.platform.usecase.vita.medicine;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;

/**
 * Taken ÷ due over a period (spec 5.2). Due counts the doses that were taken, skipped or missed;
 * an as-needed medicine has no due doses and a paused medicine makes none, so both stay out.
 * Skipped and missed both count as not taken and are shown apart. Pure.
 */
public final class AdherenceCalculator {

  private AdherenceCalculator() {}

  /** One dose: its status and whether it was marked late. */
  public record Dose(String status, boolean late) {}

  /**
   * @param exact   taken ÷ due as a percentage with two decimals (85.71), null with nothing due
   * @param percent the same rounded half up to a whole number (86), null with nothing due
   */
  public record Result(int due, int taken, int skipped, int missed, int late, BigDecimal exact, Integer percent) {}

  public static Result of(Collection<Dose> doses) {
    int taken = 0;
    int skipped = 0;
    int missed = 0;
    int late = 0;
    for (Dose d : doses) {
      switch (d.status()) {
        case "taken" -> {
          taken++;
          if (d.late()) late++;
        }
        case "skipped" -> skipped++;
        case "missed" -> missed++;
        default -> { }
      }
    }
    int due = taken + skipped + missed;
    if (due == 0) return new Result(0, 0, 0, 0, 0, null, null);
    BigDecimal ratio = BigDecimal.valueOf(taken).multiply(BigDecimal.valueOf(100))
        .divide(BigDecimal.valueOf(due), 10, RoundingMode.HALF_UP);
    return new Result(due, taken, skipped, missed, late, ratio.setScale(2, RoundingMode.HALF_UP),
        ratio.setScale(0, RoundingMode.HALF_UP).intValueExact());
  }
}
