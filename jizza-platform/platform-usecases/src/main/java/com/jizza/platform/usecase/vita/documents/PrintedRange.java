package com.jizza.platform.usecase.vita.documents;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A reference range as the lab printed it (spec 5.3, "Ranges"). {@code <200}, {@code >40},
 * "Up to 1.2" and {@code 4.0 - 5.6} are read into bounds; anything else, such as a range printed
 * as several text bands ("Desirable: < 200; Borderline: 200-240"), is kept only as text and its
 * flag is {@code unknown}. Nothing here decides what a range ought to be.
 */
public final class PrintedRange {

  /**
   * @param low  the lower bound, or null
   * @param high the upper bound, or null
   * @param text the range as printed when it could not be read into bounds; null otherwise
   * @param unsure a bound needed a guess (a decimal comma)
   */
  public record Range(BigDecimal low, BigDecimal high, boolean lowInclusive, boolean highInclusive,
                      String text, boolean unsure) {
    public boolean bounded() {
      return low != null || high != null;
    }

    /** The lab flag this range gives a value: low, normal, high, or unknown. */
    public String flagFor(BigDecimal value) {
      if (value == null || !bounded()) return "unknown";
      if (low != null && (lowInclusive ? value.compareTo(low) < 0 : value.compareTo(low) <= 0)) return "low";
      if (high != null && (highInclusive ? value.compareTo(high) > 0 : value.compareTo(high) >= 0)) return "high";
      return "normal";
    }
  }

  private static final String NUM = "([0-9][0-9,]*(?:\\.[0-9]+)?)";
  private static final Pattern BETWEEN = Pattern.compile(NUM + "\\s*(?:-|–|—|to)\\s*" + NUM);
  private static final Pattern BELOW = Pattern.compile(
      "(<=|=<|≤|<|less than or equal to|less than|below|up ?to|upto|max(?:imum)?)\\s*:?\\s*" + NUM);
  private static final Pattern ABOVE = Pattern.compile(
      "(>=|=>|≥|>|more than or equal to|more than|greater than|above|min(?:imum)?)\\s*:?\\s*" + NUM);

  private PrintedRange() {}

  /** The range, or null when nothing was printed. */
  public static Range parse(String printed) {
    if (printed == null || printed.isBlank()) return null;
    String s = printed.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    Matcher m = BETWEEN.matcher(s);
    if (m.matches()) {
      PrintedNumber.Parsed lo = PrintedNumber.parse(m.group(1));
      PrintedNumber.Parsed hi = PrintedNumber.parse(m.group(2));
      if (lo != null && hi != null && lo.value().compareTo(hi.value()) <= 0) {
        return new Range(lo.value(), hi.value(), true, true, null, lo.unsure() || hi.unsure());
      }
    }
    m = BELOW.matcher(s);
    if (m.matches()) {
      PrintedNumber.Parsed hi = PrintedNumber.parse(m.group(2));
      String op = m.group(1);
      boolean inclusive = !op.equals("<") && !op.startsWith("less than") && !op.equals("below")
          || op.contains("equal");
      if (hi != null) return new Range(null, hi.value(), false, inclusive, null, hi.unsure());
    }
    m = ABOVE.matcher(s);
    if (m.matches()) {
      PrintedNumber.Parsed lo = PrintedNumber.parse(m.group(2));
      String op = m.group(1);
      boolean inclusive = !op.equals(">") && !op.startsWith("more than") && !op.startsWith("greater than")
          && !op.equals("above") || op.contains("equal");
      if (lo != null) return new Range(lo.value(), null, inclusive, false, null, lo.unsure());
    }
    return new Range(null, null, false, false, printed.strip(), false);
  }
}
