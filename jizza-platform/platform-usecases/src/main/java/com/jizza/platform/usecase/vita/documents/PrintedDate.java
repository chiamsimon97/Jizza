package com.jizza.platform.usecase.vita.documents;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A date as an Indian document prints it (spec 5.3, "Dates"): day first, so {@code 05/11/2026} is
 * 5 November. Also "15 Sep 2026", "15-Sep-26" and ISO dates. A time printed after the date is
 * ignored. Two-digit years are this century.
 */
public final class PrintedDate {

  private static final Pattern NUMERIC = Pattern.compile("(\\d{1,2})[/.\\-](\\d{1,2})[/.\\-](\\d{2}|\\d{4})(?:\\b.*)?");
  private static final Pattern ISO = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})(?:\\b.*)?");
  private static final Pattern WORDS = Pattern.compile(
      "(\\d{1,2})(?:st|nd|rd|th)?[\\s\\-/.,]*([a-z]{3,9})[\\s\\-/.,]*(\\d{2}|\\d{4})(?:\\b.*)?");
  private static final Map<String, Integer> MONTHS = Map.ofEntries(
      Map.entry("jan", 1), Map.entry("feb", 2), Map.entry("mar", 3), Map.entry("apr", 4),
      Map.entry("may", 5), Map.entry("jun", 6), Map.entry("jul", 7), Map.entry("aug", 8),
      Map.entry("sep", 9), Map.entry("oct", 10), Map.entry("nov", 11), Map.entry("dec", 12));

  private PrintedDate() {}

  /** The date, or null when the text is not a date this can read without guessing. */
  public static LocalDate parse(String printed) {
    if (printed == null) return null;
    String s = printed.strip().toLowerCase(Locale.ROOT);
    try {
      Matcher m = ISO.matcher(s);
      if (m.matches()) return LocalDate.of(i(m.group(1)), i(m.group(2)), i(m.group(3)));
      m = NUMERIC.matcher(s);
      if (m.matches()) return LocalDate.of(year(m.group(3)), i(m.group(2)), i(m.group(1)));
      m = WORDS.matcher(s);
      if (m.matches()) {
        Integer month = MONTHS.get(m.group(2).substring(0, 3));
        if (month == null) return null;
        return LocalDate.of(year(m.group(3)), month, i(m.group(1)));
      }
    } catch (DateTimeException impossible) {
      return null;
    }
    return null;
  }

  private static int year(String y) {
    return y.length() == 2 ? 2000 + i(y) : i(y);
  }

  private static int i(String s) {
    return Integer.parseInt(s);
  }
}
