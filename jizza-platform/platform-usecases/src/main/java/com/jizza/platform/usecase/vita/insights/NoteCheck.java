package com.jizza.platform.usecase.vita.insights;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The check every sentence of a weekly note passes (spec 5.12, Phase 4): it names at least one row
 * it came from, every row it names is one the note was built from, and every number in its text is
 * the count of its rows or a value, or a part of a date or time, held in those rows. A sentence
 * that fails is never shown; whoever words the note (a template today, a model later) is held to
 * the same check.
 */
public final class NoteCheck {

  private static final Pattern NUMBER = Pattern.compile("\\d+(?:\\.\\d+)?");
  private static final Pattern UUID_TEXT = Pattern.compile("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

  private NoteCheck() {}

  /** One row a sentence came from: its table and id. */
  public record Ref(String table, String id) {}

  /** A sentence and the rows behind it. */
  public record Sentence(String kind, String text, List<Ref> rows) {}

  /**
   * Null when the sentence passes; otherwise why it was rejected. {@code rows} holds every row the
   * note was built from, by id.
   */
  public static String problem(Sentence s, Map<String, ObjectNode> rows, ZoneId zone) {
    if (s.text() == null || s.text().isBlank()) return "empty sentence";
    if (s.rows() == null || s.rows().isEmpty()) return "no row behind it";
    Set<String> allowed = new HashSet<>();
    allowed.add(String.valueOf(s.rows().size()));
    for (Ref r : s.rows()) {
      ObjectNode row = rows.get(r.id());
      if (row == null) return "a row it names is not one the note was built from";
      collect(row, allowed, zone);
    }
    Matcher m = NUMBER.matcher(s.text());
    while (m.find()) {
      if (!allowed.contains(plain(m.group()))) return "the number " + m.group() + " is not in its rows";
    }
    return null;
  }

  /** The numbers a row holds: its values, and the day, month number, year, hour and minute of its dates. */
  static void collect(JsonNode node, Set<String> out, ZoneId zone) {
    if (node == null || node.isNull()) return;
    if (node.isNumber()) {
      out.add(plain(node.decimalValue().toPlainString()));
    } else if (node.isTextual()) {
      String s = node.asText();
      if (UUID_TEXT.matcher(s).matches()) return;
      LocalDate d = date(s);
      ZonedDateTime t = time(s, zone);
      if (t != null) {
        add(out, t.getDayOfMonth(), t.getMonthValue(), t.getYear(), t.getHour(), t.getMinute());
      } else if (d != null) {
        add(out, d.getDayOfMonth(), d.getMonthValue(), d.getYear());
      } else {
        // Text the record holds, such as "Metformin 500", carries its numbers with it.
        Matcher m = NUMBER.matcher(s);
        while (m.find()) out.add(plain(m.group()));
      }
    } else if (node.isObject()) {
      // Ids are not values: their digits never vouch for a number.
      for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
        Map.Entry<String, JsonNode> e = it.next();
        String k = e.getKey();
        if (k.equals("id") || k.endsWith("Id") || k.endsWith("Ids") || k.equals("sourceRef") || k.equals("externalId")
            || k.equals("createdAt") || k.equals("updatedAt") || k.equals("deletedAt")) continue;
        collect(e.getValue(), out, zone);
      }
    } else if (node.isArray()) {
      for (Iterator<JsonNode> it = node.elements(); it.hasNext(); ) collect(it.next(), out, zone);
    }
  }

  private static void add(Set<String> out, int... parts) {
    for (int p : parts) {
      out.add(String.valueOf(p));
      out.add(String.format("%02d", p));
    }
  }

  private static String plain(String s) {
    try {
      return new BigDecimal(s).stripTrailingZeros().toPlainString();
    } catch (NumberFormatException e) {
      return s;
    }
  }

  private static LocalDate date(String s) {
    if (s.length() != 10) return null;
    try {
      return LocalDate.parse(s);
    } catch (DateTimeParseException e) {
      return null;
    }
  }

  private static ZonedDateTime time(String s, ZoneId zone) {
    if (s.length() < 16 || s.charAt(10) != 'T') return null;
    try {
      return OffsetDateTime.parse(s).atZoneSameInstant(zone);
    } catch (DateTimeParseException e) {
      try {
        return Instant.parse(s).atZone(zone);
      } catch (DateTimeParseException e2) {
        return null;
      }
    }
  }
}
