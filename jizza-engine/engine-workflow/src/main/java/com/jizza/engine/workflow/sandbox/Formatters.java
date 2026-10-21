package com.jizza.engine.workflow.sandbox;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * The {@code fmt:} functions every workflow expression can call, the counterpart of Zapier's
 * Formatter and Zoho Flow's built-in functions: text, numbers, dates and lookups, without an agent.
 *
 * <p>Each function takes and returns plain values, never touches anything outside its arguments,
 * and treats a missing value as empty rather than failing, so a step does not die on a blank field.
 * Dates are read and written in India's time zone unless one is given, like scheduled tasks.
 */
public final class Formatters {

  private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Kolkata");
  private static final int MAX_TEXT = 100_000;

  private Formatters() {}

  // ── text ────────────────────────────────────────────────────────────────

  public static String upper(Object s) { return text(s).toUpperCase(Locale.ROOT); }

  public static String lower(Object s) { return text(s).toLowerCase(Locale.ROOT); }

  public static String trim(Object s) { return text(s).strip(); }

  public static String titleCase(Object s) {
    StringBuilder out = new StringBuilder();
    boolean start = true;
    for (char c : text(s).toCharArray()) {
      out.append(start ? Character.toUpperCase(c) : Character.toLowerCase(c));
      start = Character.isWhitespace(c) || c == '-';
    }
    return out.toString();
  }

  public static String truncate(Object s, Number length) {
    String value = text(s);
    int max = Math.max(0, length == null ? 0 : length.intValue());
    return value.length() <= max ? value : value.substring(0, max);
  }

  public static String replace(Object s, Object find, Object with) {
    String needle = text(find);
    return needle.isEmpty() ? text(s) : text(s).replace(needle, text(with));
  }

  public static boolean contains(Object s, Object part) { return text(s).contains(text(part)); }

  public static List<String> split(Object s, Object separator) {
    String sep = text(separator);
    if (text(s).isEmpty()) return List.of();
    if (sep.isEmpty()) return List.of(text(s));
    return Arrays.stream(text(s).split(java.util.regex.Pattern.quote(sep), -1)).map(String::strip)
        .toList();
  }

  public static String join(Object items, Object separator) {
    List<String> parts = new ArrayList<>();
    for (Object item : list(items)) parts.add(text(item));
    return String.join(text(separator), parts);
  }

  /** The first email address in the text, or empty. */
  public static String extractEmail(Object s) {
    var m = java.util.regex.Pattern.compile("[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+").matcher(text(s));
    return m.find() ? m.group() : "";
  }

  /** The first number in the text, or null. */
  public static Double extractNumber(Object s) {
    var m = java.util.regex.Pattern.compile("-?\\d+(?:[.,]\\d+)*").matcher(text(s));
    return m.find() ? toNumber(m.group()) : null;
  }

  public static String defaultIfEmpty(Object s, Object fallback) {
    return text(s).isBlank() ? text(fallback) : text(s);
  }

  // ── numbers ─────────────────────────────────────────────────────────────

  /** Reads "1,234.50", "₹ 99" or 12 as a number; null when there is no number. */
  public static Double toNumber(Object value) {
    if (value instanceof Number n) return n.doubleValue();
    String cleaned = text(value).replaceAll("[^0-9.\\-]", "");
    if (cleaned.isEmpty() || cleaned.equals("-") || cleaned.equals(".")) return null;
    try {
      return Double.parseDouble(cleaned);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  public static Double round(Object value, Number places) {
    Double n = toNumber(value);
    if (n == null) return null;
    int scale = places == null ? 0 : places.intValue();
    return BigDecimal.valueOf(n).setScale(scale, RoundingMode.HALF_UP).doubleValue();
  }

  /** Grouped digits with the given decimal places (two by default), such as "123,456.50". */
  public static String formatNumber(Object value, Number places) {
    Double n = toNumber(value);
    if (n == null) return "";
    NumberFormat format = NumberFormat.getNumberInstance(Locale.forLanguageTag("en-IN"));
    int scale = places == null ? 2 : places.intValue();
    format.setMinimumFractionDigits(scale);
    format.setMaximumFractionDigits(scale);
    return format.format(n);
  }

  public static String formatCurrency(Object value, Object currencyCode) {
    Double n = toNumber(value);
    if (n == null) return "";
    String code = text(currencyCode).isBlank() ? "INR" : text(currencyCode).toUpperCase(Locale.ROOT);
    NumberFormat format = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("en-IN"));
    format.setCurrency(java.util.Currency.getInstance(code));
    return format.format(n);
  }

  public static Double sum(Object items) {
    double total = 0;
    for (Object item : list(items)) {
      Double n = toNumber(item);
      if (n != null) total += n;
    }
    return total;
  }

  public static Double min(Object a, Object b) {
    Double x = toNumber(a);
    Double y = toNumber(b);
    return x == null ? y : y == null ? x : Math.min(x, y);
  }

  public static Double max(Object a, Object b) {
    Double x = toNumber(a);
    Double y = toNumber(b);
    return x == null ? y : y == null ? x : Math.max(x, y);
  }

  // ── dates ───────────────────────────────────────────────────────────────

  /** The current moment, as an ISO-8601 time with India's offset. */
  public static String now() { return OffsetDateTime.now(DEFAULT_ZONE).toString(); }

  public static String today() { return LocalDate.now(DEFAULT_ZONE).toString(); }

  /** A date or time in the given pattern (Java style, e.g. "dd MMM yyyy" or "HH:mm"). */
  public static String formatDate(Object date, Object pattern) {
    return formatDate(date, pattern, null);
  }

  public static String formatDate(Object date, Object pattern, Object zone) {
    ZonedDateTime moment = moment(date, zone);
    if (moment == null) return "";
    String p = text(pattern).isBlank() ? "yyyy-MM-dd" : text(pattern);
    return moment.format(DateTimeFormatter.ofPattern(p, Locale.ENGLISH));
  }

  /** Reads a date in the given pattern and returns it as an ISO-8601 time. */
  public static String parseDate(Object text, Object pattern) {
    String value = text(text);
    if (value.isBlank()) return "";
    DateTimeFormatter f = DateTimeFormatter.ofPattern(text(pattern), Locale.ENGLISH);
    try {
      return LocalDateTime.parse(value, f).atZone(DEFAULT_ZONE).toOffsetDateTime().toString();
    } catch (DateTimeException notDateTime) {
      return LocalDate.parse(value, f).atStartOfDay(DEFAULT_ZONE).toOffsetDateTime().toString();
    }
  }

  /** Adds an ISO-8601 duration ("P3D", "PT2H", "-P1D") or a number of days to a date. */
  public static String addTime(Object date, Object amount) {
    ZonedDateTime moment = moment(date, null);
    if (moment == null) return "";
    if (amount instanceof Number days) return moment.plusDays(days.longValue()).toOffsetDateTime().toString();
    String a = text(amount).strip();
    boolean negative = a.startsWith("-");
    String spec = negative ? a.substring(1) : a;
    ZonedDateTime result;
    if (spec.contains("T")) {
      Duration d = Duration.parse(spec);
      result = negative ? moment.minus(d) : moment.plus(d);
    } else {
      java.time.Period p = java.time.Period.parse(spec);
      result = negative ? moment.minus(p) : moment.plus(p);
    }
    return result.toOffsetDateTime().toString();
  }

  public static String addDays(Object date, Number days) {
    return addTime(date, days == null ? 0 : days.longValue());
  }

  /** Whole days from the first date to the second; negative if the second is earlier. */
  public static Long daysBetween(Object from, Object to) {
    ZonedDateTime a = moment(from, null);
    ZonedDateTime b = moment(to, null);
    if (a == null || b == null) return null;
    return java.time.temporal.ChronoUnit.DAYS.between(a.toLocalDate(), b.toLocalDate());
  }

  // ── lookups and lists ───────────────────────────────────────────────────

  /** Zapier's lookup table: the value for {@code key} in {@code table}, else {@code fallback}. */
  public static Object lookup(Object table, Object key, Object fallback) {
    if (table instanceof Map<?, ?> map) {
      Object found = map.get(text(key));
      if (found != null) return found;
    }
    return fallback;
  }

  public static int count(Object items) {
    if (items instanceof Map<?, ?> map) return map.size();
    return list(items).size();
  }

  public static Object first(Object items) {
    List<Object> all = list(items);
    return all.isEmpty() ? null : all.get(0);
  }

  public static Object last(Object items) {
    List<Object> all = list(items);
    return all.isEmpty() ? null : all.get(all.size() - 1);
  }

  /** The {@code field} of every item that has one, such as every line item's price. */
  public static List<Object> pluck(Object items, Object field) {
    List<Object> out = new ArrayList<>();
    for (Object item : list(items)) {
      if (item instanceof Map<?, ?> map && map.get(text(field)) != null) out.add(map.get(text(field)));
    }
    return out;
  }

  public static List<Object> unique(Object items) {
    return list(items).stream().filter(Objects::nonNull).distinct().toList();
  }

  public static boolean isEmpty(Object value) {
    if (value == null) return true;
    if (value instanceof Collection<?> c) return c.isEmpty();
    if (value instanceof Map<?, ?> m) return m.isEmpty();
    return text(value).isBlank();
  }

  // ── helpers ─────────────────────────────────────────────────────────────

  private static String text(Object value) {
    if (value == null) return "";
    String s = String.valueOf(value);
    return s.length() > MAX_TEXT ? s.substring(0, MAX_TEXT) : s;
  }

  private static List<Object> list(Object value) {
    if (value == null) return List.of();
    if (value instanceof Collection<?> c) return new ArrayList<>(c);
    if (value.getClass().isArray()) {
      List<Object> out = new ArrayList<>();
      for (int i = 0; i < java.lang.reflect.Array.getLength(value); i++) {
        out.add(java.lang.reflect.Array.get(value, i));
      }
      return out;
    }
    return List.of(value);
  }

  private static ZonedDateTime moment(Object value, Object zone) {
    String s = text(value).strip();
    if (s.isEmpty()) return null;
    ZoneId zoneId = text(zone).isBlank() ? DEFAULT_ZONE : ZoneId.of(text(zone));
    try {
      return OffsetDateTime.parse(s).atZoneSameInstant(zoneId);
    } catch (DateTimeException a) {
      try {
        return Instant.parse(s).atZone(zoneId);
      } catch (DateTimeException b) {
        try {
          return LocalDateTime.parse(s).atZone(zoneId);
        } catch (DateTimeException c) {
          return LocalDate.parse(s).atStartOfDay(zoneId);
        }
      }
    }
  }
}
