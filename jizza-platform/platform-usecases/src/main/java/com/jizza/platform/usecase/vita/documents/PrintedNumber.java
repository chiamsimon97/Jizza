package com.jizza.platform.usecase.vita.documents;

import java.math.BigDecimal;
import java.util.regex.Pattern;

/**
 * A number as an Indian report prints it (spec 5.3, "Numbers"). A comma followed by exactly three
 * digits, or the Indian 2-2-3 grouping, is digit grouping: {@code 7,500} is 7500 and
 * {@code 2,50,000} is 250000. A comma followed by one or two digits may be a decimal comma:
 * {@code 0,9} reads as 0.9 and is marked unsure, so the review outlines it.
 */
public final class PrintedNumber {

  /** @param unsure true when the reading needed a guess (a decimal comma) */
  public record Parsed(BigDecimal value, boolean unsure) {}

  private static final Pattern PLAIN = Pattern.compile("[-+]?\\d+(\\.\\d+)?");
  private static final Pattern WESTERN = Pattern.compile("\\d{1,3}(,\\d{3})+(\\.\\d+)?");
  private static final Pattern INDIAN = Pattern.compile("\\d{1,2}(,\\d{2})+,\\d{3}(\\.\\d+)?");
  private static final Pattern DECIMAL_COMMA = Pattern.compile("\\d+,\\d{1,2}");

  private PrintedNumber() {}

  /** The number, or null when the text is not one number (a word, a range, a blank). */
  public static Parsed parse(String printed) {
    if (printed == null) return null;
    String s = printed.strip().replace(" ", "").replace(" ", "");
    if (s.isEmpty()) return null;
    if (PLAIN.matcher(s).matches()) return new Parsed(new BigDecimal(s.startsWith("+") ? s.substring(1) : s), false);
    if (WESTERN.matcher(s).matches() || INDIAN.matcher(s).matches()) {
      return new Parsed(new BigDecimal(s.replace(",", "")), false);
    }
    if (DECIMAL_COMMA.matcher(s).matches()) return new Parsed(new BigDecimal(s.replace(',', '.')), true);
    return null;
  }
}
