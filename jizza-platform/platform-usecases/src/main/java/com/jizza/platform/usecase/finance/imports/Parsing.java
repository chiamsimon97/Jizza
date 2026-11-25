package com.jizza.platform.usecase.finance.imports;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoField;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Dates, amounts and header names as Indian statements write them. */
final class Parsing {

  private Parsing() {}

  private static final List<DateTimeFormatter> DATES = List.of(
      fmt("d-M-uuuu"), fmt("d-MMM-uuuu"), fmt("d-MMMM-uuuu"), fmt("uuuu-M-d"), twoDigit("d-M-"),
      twoDigit("d-MMM-"));
  private static final Pattern TIME = Pattern.compile("[\\sT]+\\d{1,2}:\\d{2}(:\\d{2}(\\.\\d+)?)?(\\s*[AaPp][Mm])?(Z|[+-]\\d{2}:?\\d{2})?$");
  private static final Pattern DR_CR = Pattern.compile("(?i)\\s*\\(?(dr|cr)\\.?\\)?$");
  private static final Pattern NUMBER = Pattern.compile("-?\\d+(\\.\\d+)?");

  private static DateTimeFormatter fmt(String pattern) {
    return new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(pattern)
        .toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT);
  }

  /** Two-digit years are 2000 to 2099. */
  private static DateTimeFormatter twoDigit(String dayMonth) {
    return new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(dayMonth)
        .appendValueReduced(ChronoField.YEAR, 2, 2, 2000).toFormatter(Locale.ENGLISH)
        .withResolverStyle(ResolverStyle.STRICT);
  }

  /** A day-first date ("05/01/2025", "05-Jan-25", "5 January 2025", "2025-01-05"); null if none. */
  static LocalDate date(String raw) {
    if (raw == null) return null;
    String s = TIME.matcher(raw.strip()).replaceFirst("").strip();
    if (s.isEmpty() || s.length() > 24) return null;
    s = s.replace(',', ' ').replaceAll("[./\\s]+", "-").replaceAll("-+", "-");
    for (DateTimeFormatter f : DATES) {
      try {
        LocalDate d = LocalDate.parse(s, f);
        if (d.getYear() < 1990 || d.getYear() > 2100) return null;
        return d;
      } catch (DateTimeParseException next) {
        // try the next layout
      }
    }
    return null;
  }

  /** An amount as written, with its sign; a trailing Dr or Cr is read as the sign. */
  record Amount(BigDecimal value, String drCr) {}

  /** Null when the cell is blank; throws {@link NumberFormatException} when it is not a number. */
  static Amount amount(String raw) {
    if (raw == null) return null;
    String s = raw.strip();
    if (s.isEmpty() || s.equals("-") || s.equals("--")) return null;
    String drCr = null;
    java.util.regex.Matcher side = DR_CR.matcher(s);
    if (side.find()) {
      drCr = side.group(1).toLowerCase(Locale.ROOT);
      s = s.substring(0, side.start()).strip();
    }
    boolean negative = false;
    if (s.startsWith("(") && s.endsWith(")")) {
      negative = true;
      s = s.substring(1, s.length() - 1);
    }
    s = s.replace("₹", "").replaceAll("(?i)^(inr|rs\\.?)", "").replace(",", "").replace(" ", "").strip();
    if (s.startsWith("+")) s = s.substring(1);
    if (s.startsWith("-")) {
      negative = !negative;
      s = s.substring(1);
    }
    if (!NUMBER.matcher(s).matches()) throw new NumberFormatException();
    BigDecimal v = new BigDecimal(s);
    return new Amount(negative ? v.negate() : v, drCr);
  }

  /** A header or cell reduced to lower-case words: "Withdrawal Amt. (INR)" is "withdrawal amt inr". */
  static String words(String raw) {
    if (raw == null) return "";
    return raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").strip();
  }

  static String sha256(String s) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  static String clip(String s, int max) {
    if (s == null) return "";
    String t = s.strip().replaceAll("\\s+", " ");
    return t.length() > max ? t.substring(0, max) : t;
  }
}
