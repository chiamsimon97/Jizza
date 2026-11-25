package com.jizza.platform.usecase.finance.imports;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.util.Json;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Finds the header row of a CSV export and which column plays which role, from header words
 * alone, so any bank's or broker's layout works as long as its headers say what they hold.
 */
final class HeaderFinder {

  private HeaderFinder() {}

  /** How many lines at the top of a file may come before its header (bank name, address…). */
  static final int SEARCH_ROWS = 40;

  /** A role and the header names that fill it, most specific first. */
  record Role(String key, List<Predicate<String>> tests) {
    static Role of(String key, List<String> exact, List<String> prefixes) {
      List<Predicate<String>> t = new ArrayList<>();
      exact.forEach(e -> t.add(h -> h.equals(e)));
      prefixes.forEach(p -> t.add(h -> h.startsWith(p)));
      return new Role(key, t);
    }
  }

  record Found(int headerRow, List<String> headers, Map<String, Integer> index) {
    ObjectNode mapping() {
      ObjectNode m = Json.obj().put("headerRow", headerRow);
      index.forEach((k, i) -> m.put(k, headers.get(i)));
      return m;
    }

    String cell(List<String> row, String role) {
      Integer i = index.get(role);
      return i == null || i >= row.size() ? null : row.get(i);
    }
  }

  /** The first row (of the top {@link #SEARCH_ROWS}) whose headers fill enough roles. */
  static Found find(List<List<String>> rows, List<Role> roles, Predicate<Set<String>> enough) {
    Found best = null;
    for (int r = 0; r < Math.min(SEARCH_ROWS, rows.size()); r++) {
      List<String> headers = rows.get(r);
      Map<String, Integer> index = match(headers, roles);
      if (enough.test(index.keySet())) return new Found(r, headers, index);
      if (best == null || index.size() > best.index().size()) best = new Found(r, headers, index);
    }
    return best;
  }

  private static Map<String, Integer> match(List<String> headers, List<Role> roles) {
    Map<String, Integer> out = new LinkedHashMap<>();
    Set<Integer> taken = new HashSet<>();
    List<String> words = headers.stream().map(Parsing::words).toList();
    for (Role role : roles) {
      search:
      for (Predicate<String> test : role.tests()) {
        for (int c = 0; c < words.size(); c++) {
          if (!taken.contains(c) && !words.get(c).isEmpty() && test.test(words.get(c))) {
            out.put(role.key(), c);
            taken.add(c);
            break search;
          }
        }
      }
    }
    return out;
  }

  /**
   * The columns the person chose: {@code {"headerRow": 3, "date": "Txn Date", …}}, each role naming
   * a header of that row. Roles not in {@code allowed} are refused.
   */
  static Found chosen(List<List<String>> rows, JsonNode mapping, Set<String> allowed) {
    int headerRow = mapping.path("headerRow").asInt(-1);
    if (headerRow < 0 || headerRow >= Math.min(rows.size(), SEARCH_ROWS)) {
      throw new ValidationException("Choose the row that holds the column names.");
    }
    List<String> headers = rows.get(headerRow);
    Map<String, Integer> index = new LinkedHashMap<>();
    var it = mapping.fields();
    while (it.hasNext()) {
      var e = it.next();
      if (e.getKey().equals("headerRow") || e.getValue().isNull() || e.getValue().asText().isBlank()) continue;
      if (!allowed.contains(e.getKey())) throw new ValidationException("Unknown column role " + e.getKey() + ".");
      String want = Parsing.words(e.getValue().asText());
      int at = -1;
      for (int c = 0; c < headers.size(); c++) {
        if (Parsing.words(headers.get(c)).equals(want) && !index.containsValue(c)) {
          at = c;
          break;
        }
      }
      if (at < 0) throw new ValidationException("This file has no column called " + Parsing.clip(e.getValue().asText(), 40) + ".");
      index.put(e.getKey(), at);
    }
    return new Found(headerRow, headers, index);
  }

  /** The column choice moved to the first row that has all its column names; null if none does. */
  static ObjectNode locate(List<List<String>> rows, JsonNode mapping) {
    if (mapping == null || !mapping.isObject()) return null;
    List<String> names = new ArrayList<>();
    mapping.fields().forEachRemaining(e -> {
      if (!e.getKey().equals("headerRow") && !e.getValue().isNull()) names.add(Parsing.words(e.getValue().asText()));
    });
    if (names.isEmpty()) return null;
    for (int r = 0; r < Math.min(SEARCH_ROWS, rows.size()); r++) {
      Set<String> have = new HashSet<>();
      rows.get(r).forEach(c -> have.add(Parsing.words(c)));
      if (have.containsAll(names)) return ((ObjectNode) mapping.deepCopy()).put("headerRow", r);
    }
    return null;
  }

  /** The first rows of the file, cut to a few cells' worth, for the column chooser. */
  static List<List<String>> sample(List<List<String>> rows, int from, int count) {
    List<List<String>> out = new ArrayList<>();
    for (int r = Math.max(0, from); r < rows.size() && out.size() < count; r++) {
      out.add(rows.get(r).stream().limit(20).map(c -> Parsing.clip(c, 60)).toList());
    }
    return out;
  }
}
