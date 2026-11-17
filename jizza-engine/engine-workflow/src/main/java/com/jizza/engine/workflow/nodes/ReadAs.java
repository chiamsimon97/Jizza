package com.jizza.engine.workflow.nodes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.util.Json;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * What a text read from a page means: "₹2,345.00" as the number 2345.00, "20-Dec-2025" as the
 * date 2025-12-20, "PNR: 4512345678" as 4512345678 by a pattern. A step that reads text and says
 * {@code readAs} keeps its {@code text} and gains a {@code value}, so a later "if price ≤ 500" or
 * a chat reply can use it. A text that does not fit leaves {@code value} out and says why in
 * {@code valueProblem}; the step itself does not fail.
 */
final class ReadAs {

  private ReadAs() {}

  private static final Pattern NUMBER = Pattern.compile("[-−]?\\d[\\d,. \\u00a0']*");
  private static final Pattern CURRENCY = Pattern.compile("(₹|Rs\\.?|INR|\\$|USD|€|EUR|£|GBP|¥|JPY|S\\$|SGD|RM|MYR|AED)",
      Pattern.CASE_INSENSITIVE);
  private static final List<String> DATE_FORMATS = List.of("d-MMM-yyyy", "d MMM yyyy",
      "d MMMM yyyy", "MMM d, yyyy", "MMMM d, yyyy", "d/M/yyyy", "d.M.yyyy", "yyyy-MM-dd",
      "d-M-yyyy", "EEE, d MMM yyyy", "EEE d MMM yyyy");

  /** The step's output with {@code value} added as {@code rule} reads its {@code text}. */
  static void apply(JsonNode rule, JsonNode output) {
    if (rule == null || !rule.isObject() || !(output instanceof ObjectNode out)) return;
    String text = out.path("text").asText(null);
    if (text == null) return;
    String as = rule.path("as").asText("text");
    try {
      switch (as) {
        case "number" -> out.set("value", Json.mapper().getNodeFactory().numberNode(number(text)));
        case "money" -> {
          out.set("value", Json.mapper().getNodeFactory().numberNode(number(text)));
          Matcher currency = CURRENCY.matcher(text);
          if (currency.find()) out.put("currency", currency.group(1));
        }
        case "date" -> out.put("value", date(text, rule.path("format").asText("")).toString());
        case "pattern" -> out.put("value", pattern(text, rule.path("pattern").asText("")));
        default -> { }
      }
    } catch (IllegalArgumentException e) {
      out.put("valueProblem", e.getMessage());
    }
  }

  /** The first number in the text: "1,234.50", "1.234,50" and "12 345" all read as numbers. */
  static BigDecimal number(String text) {
    Matcher m = NUMBER.matcher(text.replace('−', '-'));
    if (!m.find()) throw new IllegalArgumentException("No number in \"" + shorten(text) + "\"");
    String raw = m.group().replaceAll("[\\s\\u00a0']", "").replace('−', '-');
    while (raw.endsWith(",") || raw.endsWith(".")) raw = raw.substring(0, raw.length() - 1);
    int dot = raw.lastIndexOf('.');
    int comma = raw.lastIndexOf(',');
    String plain;
    if (dot >= 0 && comma >= 0) {
      // The later one is the decimal mark.
      plain = dot > comma ? raw.replace(",", "") : raw.replace(".", "").replace(',', '.');
    } else if (comma >= 0) {
      // "1,234" is a thousand; "12,5" is a decimal.
      plain = raw.length() - comma - 1 == 3 && raw.indexOf(',') == comma
          || raw.indexOf(',') != comma ? raw.replace(",", "") : raw.replace(',', '.');
    } else if (dot >= 0 && raw.indexOf('.') != dot) {
      plain = raw.replace(".", "");  // "1.234.567"
    } else {
      plain = raw;
    }
    try {
      return new BigDecimal(plain);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("No number in \"" + shorten(text) + "\"");
    }
  }

  /** The date in the text, by the given format (as the site writes it), else common ones. */
  static LocalDate date(String text, String format) {
    String clean = text.strip().replaceAll("\\s+", " ").replaceAll("(\\d)(st|nd|rd|th)\\b", "$1");
    List<String> formats = format.isBlank() ? DATE_FORMATS : List.of(format);
    for (String f : formats) {
      DateTimeFormatter formatter;
      try {
        formatter = new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(f)
            .toFormatter(Locale.ENGLISH);
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException("'" + format + "' is not a date format");
      }
      // The date may sit inside other words: try each run of words as long as the format.
      String[] words = clean.split(" ");
      int span = f.split(" ").length;
      for (int i = 0; i + span <= words.length; i++) {
        String candidate = String.join(" ", java.util.Arrays.copyOfRange(words, i, i + span))
            .replaceAll("^[^\\p{Alnum}]+|[^\\p{Alnum}]+$", "");
        try {
          return LocalDate.parse(candidate, formatter);
        } catch (DateTimeParseException e) {
          // Try the next.
        }
      }
    }
    throw new IllegalArgumentException("No date in \"" + shorten(text) + "\"");
  }

  /** The pattern's first group (or the whole match when it has none). */
  static String pattern(String text, String regex) {
    if (regex.isBlank()) throw new IllegalArgumentException("Say what pattern to look for");
    Pattern p;
    try {
      p = Pattern.compile(regex);
    } catch (PatternSyntaxException e) {
      throw new IllegalArgumentException("The pattern '" + shorten(regex) + "' is not valid");
    }
    Matcher m = p.matcher(text.length() > 10_000 ? text.substring(0, 10_000) : text);
    if (!m.find()) throw new IllegalArgumentException("\"" + shorten(text) + "\" does not match");
    return m.groupCount() >= 1 && m.group(1) != null ? m.group(1) : m.group();
  }

  private static String shorten(String s) {
    return s.length() > 60 ? s.substring(0, 60) + "…" : s;
  }
}
