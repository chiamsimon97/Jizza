package com.jizza.engine.workflow.nodes;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which item of a list, as a page or an app lists it now, the run means. "Ramayana" finds
 * "Ramayana (Hindi)"; "Ramayana, Hindi, 3D" finds the one card whose text has all three; a date
 * ("2026-11-09", "9 Nov", "tomorrow") finds the day in a date strip that reads "Sun 09 Nov". When
 * several items fit equally, the step's {@code prefer} settles it (the first, or the cheapest or
 * dearest by the price in its text), or nobody guesses and the person is asked with those items.
 */
final class ItemMatch {

  private ItemMatch() {}

  /**
   * One listed item: what it is called, all its text, its place in the list, and the name the
   * card shows for it, which differs from the label only when two items share one ("Ramayana ·
   * Hindi 3D").
   */
  record Item(String label, String text, int index, String display) {
    Item(String label, String text) {
      this(label, text, -1, label);
    }

    String all() { return text == null || text.isBlank() ? label : label + " " + text; }

    /** Another item has the same label, so the item is clicked by its place in the list. */
    boolean byIndex() { return !display.equals(label) && index >= 0; }
  }

  /** The item chosen, or null with the items that tied (empty when nothing fit). */
  record Pick(String chosen, List<String> tied) {
    static Pick none() { return new Pick(null, List.of()); }
    boolean found() { return chosen != null; }
  }

  static List<Item> items(JsonNode listed) {
    List<String[]> rows = new ArrayList<>();
    Map<String, Integer> counts = new LinkedHashMap<>();
    java.util.Set<String> seen = new java.util.HashSet<>();
    int position = 0;
    for (JsonNode item : listed) {
      int index = item.path("index").isInt() ? item.get("index").asInt() : position;
      position++;
      String label = item.isTextual() ? item.asText().trim() : item.path("label").asText("").trim();
      String text = item.isTextual() ? "" : item.path("text").asText("").trim();
      if (label.isEmpty()) label = text;
      if (label.isEmpty()) continue;
      if (label.length() > 200) label = label.substring(0, 200);
      if (text.length() > 400) text = text.substring(0, 400);
      if (!seen.add(label + "\u0000" + text)) continue;
      rows.add(new String[] {label, text, String.valueOf(index)});
      counts.merge(label, 1, Integer::sum);
    }
    List<Item> out = new ArrayList<>();
    java.util.Set<String> displays = new java.util.HashSet<>();
    for (String[] row : rows) {
      String display = row[0];
      if (counts.get(row[0]) > 1) {
        String rest = row[1].startsWith(row[0]) ? row[1].substring(row[0].length()).trim() : row[1];
        rest = rest.replaceAll("^[\\s·,:|-]+", "");
        display = rest.isEmpty() ? row[0] : row[0] + " · "
            + (rest.length() > 60 ? rest.substring(0, 60).trim() + "…" : rest);
      }
      String unique = display;
      for (int n = 2; !displays.add(unique); n++) unique = display + " (" + n + ")";
      out.add(new Item(row[0], row[1], Integer.parseInt(row[2]), unique));
    }
    return out;
  }

  static Pick match(List<Item> items, String wanted, String prefer) {
    return match(items, wanted, prefer, LocalDate.now(ZoneId.systemDefault()));
  }

  static Pick match(List<Item> items, String wanted, String prefer, LocalDate today) {
    if (items == null || items.isEmpty()) return Pick.none();
    boolean ask = prefer == null || prefer.isBlank() || "ask".equals(prefer);
    if (wanted == null || fold(wanted).isEmpty()) {
      return ask ? Pick.none() : settle(items, prefer);
    }
    String want = fold(wanted);
    for (Item item : items) if (fold(item.display()).equals(want)) return new Pick(item.display(), List.of());
    List<Item> named = filter(items, i -> fold(i.label()).equals(want));
    if (named.size() == 1) return new Pick(named.get(0).display(), List.of());

    List<Item> fits;
    Optional<LocalDate> date = date(wanted, today);
    if (date.isPresent()) {
      fits = byDate(items, date.get(), today);
    } else {
      List<String> terms = new ArrayList<>();
      for (String part : wanted.split("[,;|]")) if (!fold(part).isEmpty()) terms.add(fold(part));
      // A label that starts with the name ("Ramayana (Hindi)"), then any card whose text has
      // every term as whole words.
      fits = terms.size() == 1
          ? filter(items, i -> fold(i.label()).startsWith(want + " ")) : List.of();
      if (fits.isEmpty()) fits = filter(items, i -> terms.stream().allMatch(t -> words(i.label(), t)));
      if (fits.isEmpty()) fits = filter(items, i -> terms.stream().allMatch(t -> words(i.all(), t)));
    }
    if (fits.isEmpty() && named.size() > 1) fits = named;
    if (fits.size() == 1) return new Pick(fits.get(0).display(), List.of());
    if (fits.isEmpty()) return Pick.none();
    return ask ? new Pick(null, fits.stream().map(Item::display).toList()) : settle(fits, prefer);
  }

  private static Pick settle(List<Item> items, String prefer) {
    if ("first".equals(prefer)) return new Pick(items.get(0).display(), List.of());
    boolean low = "lowestPrice".equals(prefer);
    Item best = null;
    BigDecimal bestPrice = null;
    for (Item item : items) {
      BigDecimal price = price(item.all());
      if (price == null) continue;
      if (bestPrice == null || (low ? price.compareTo(bestPrice) < 0 : price.compareTo(bestPrice) > 0)) {
        best = item;
        bestPrice = price;
      }
    }
    return best == null ? new Pick(null, items.stream().map(Item::display).toList())
        : new Pick(best.display(), List.of());
  }

  private static List<Item> filter(List<Item> items, Predicate<Item> test) {
    return items.stream().filter(test).toList();
  }

  private static boolean words(String text, String term) {
    return (" " + fold(text) + " ").contains(" " + term + " ");
  }

  static String fold(String text) {
    if (text == null) return "";
    String plain = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    return plain.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
  }

  // ── prices ──────────────────────────────────────────────────────────────

  private static final Pattern PRICE = Pattern.compile(
      "(?:₹|rs\\.?|inr|\\$|usd|€|eur|£|gbp)\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)"
          + "|([0-9][0-9,]*(?:\\.[0-9]{1,2})?)\\s*(?:₹|rs\\b|inr\\b|usd\\b|eur\\b|€|gbp\\b)",
      Pattern.CASE_INSENSITIVE);

  /** The lowest price written in the text, or null when it shows none. */
  static BigDecimal price(String text) {
    if (text == null) return null;
    Matcher m = PRICE.matcher(text);
    BigDecimal lowest = null;
    while (m.find()) {
      String number = (m.group(1) != null ? m.group(1) : m.group(2)).replace(",", "");
      try {
        BigDecimal value = new BigDecimal(number);
        if (lowest == null || value.compareTo(lowest) < 0) lowest = value;
      } catch (NumberFormatException ignored) {
        // Not a number after all.
      }
    }
    return lowest;
  }

  // ── dates ───────────────────────────────────────────────────────────────

  private static final List<String> MONTHS = List.of("january", "february", "march", "april",
      "may", "june", "july", "august", "september", "october", "november", "december");

  private static int month(String word) {
    if (word.length() < 3) return 0;
    for (int i = 0; i < MONTHS.size(); i++) if (MONTHS.get(i).startsWith(word)) return i + 1;
    return 0;
  }

  /** The date the wanted text names, when it is one: ISO, day-first numbers, or words. */
  static Optional<LocalDate> date(String wanted, LocalDate today) {
    String text = wanted.trim().toLowerCase(Locale.ROOT);
    if (text.equals("today")) return Optional.of(today);
    if (text.equals("tomorrow")) return Optional.of(today.plusDays(1));
    Matcher iso = Pattern.compile("^(\\d{4})-(\\d{2})-(\\d{2})(?:[t ].*)?$").matcher(text);
    try {
      if (iso.matches()) {
        return Optional.of(LocalDate.of(Integer.parseInt(iso.group(1)),
            Integer.parseInt(iso.group(2)), Integer.parseInt(iso.group(3))));
      }
      Matcher num = Pattern.compile("^(\\d{1,2})[/.-](\\d{1,2})(?:[/.-](\\d{2}|\\d{4}))?$").matcher(text);
      if (num.matches()) {
        int day = Integer.parseInt(num.group(1));
        int month = Integer.parseInt(num.group(2));
        if (month > 12 && day <= 12) {
          int swap = day;
          day = month;
          month = swap;
        }
        int year = num.group(3) == null ? today.getYear()
            : num.group(3).length() == 2 ? 2000 + Integer.parseInt(num.group(3))
            : Integer.parseInt(num.group(3));
        return Optional.of(LocalDate.of(year, month, day));
      }
      // 9 Nov, 9th November 2026, Nov 9, Sunday 9 November
      List<String> words = List.of(fold(text).split(" "));
      Integer day = null;
      int month = 0;
      Integer year = null;
      for (String w : words) {
        String digits = w.replaceAll("(st|nd|rd|th)$", "");
        if (digits.matches("\\d{4}")) year = Integer.parseInt(digits);
        else if (digits.matches("\\d{1,2}") && day == null) day = Integer.parseInt(digits);
        else if (month == 0 && month(w) > 0 && !isWeekday(w)) month = month(w);
        else if (!isWeekday(w)) return Optional.empty();
      }
      if (day == null || month == 0) return Optional.empty();
      return Optional.of(LocalDate.of(year == null ? today.getYear() : year, month, day));
    } catch (java.time.DateTimeException | NumberFormatException e) {
      return Optional.empty();
    }
  }

  private static boolean isWeekday(String word) {
    return word.length() >= 3 && List.of("monday", "tuesday", "wednesday", "thursday", "friday",
        "saturday", "sunday").stream().anyMatch(d -> d.startsWith(word));
  }

  /**
   * The items that are that day: by day and month in their text ("Sun 09 Nov"), by "today" or
   * "tomorrow", or, when no item names a month (a strip under a month heading), by the day alone.
   */
  private static List<Item> byDate(List<Item> items, LocalDate date, LocalDate today) {
    List<Item> full = new ArrayList<>();
    List<Item> dayOnly = new ArrayList<>();
    boolean anyMonth = false;
    for (Item item : items) {
      List<String> words = List.of(fold(item.all()).split(" "));
      Integer day = null;
      int month = 0;
      for (String w : words) {
        String digits = w.replaceAll("(st|nd|rd|th)$", "");
        if (day == null && digits.matches("\\d{1,2}")) day = Integer.parseInt(digits);
        else if (month == 0 && !isWeekday(w)) month = month(w);
      }
      if (month > 0) anyMonth = true;
      boolean relative = words.contains("today") && date.equals(today)
          || words.contains("tomorrow") && date.equals(today.plusDays(1));
      if (relative || day != null && day == date.getDayOfMonth() && month == date.getMonthValue()) {
        full.add(item);
      } else if (day != null && day == date.getDayOfMonth() && month == 0) {
        dayOnly.add(item);
      }
    }
    if (!full.isEmpty()) return full;
    return anyMonth ? List.of() : dayOnly;
  }
}
