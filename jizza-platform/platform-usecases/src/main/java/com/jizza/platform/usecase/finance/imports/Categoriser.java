package com.jizza.platform.usecase.finance.imports;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Puts a bank row into one of the person's categories (and so into Need, Want or Save). The
 * person's own rules ("always for rows like this") come first, then a short list of plain words
 * that say what a payment is. Anything else is left for the person: Kai never guesses.
 */
final class Categoriser {

  /** Words that say nothing about what a payment was for. */
  private static final Set<String> NOISE = Set.of("upi", "neft", "imps", "rtgs", "nach", "ach", "pos",
      "ecs", "ref", "txn", "trf", "transfer", "payment", "paid", "from", "the", "and", "for", "via",
      "ltd", "pvt", "private", "limited", "inb", "mob", "mmt", "debit", "credit", "card", "bil",
      "onl", "net", "banking", "fund", "transf", "inward", "outward", "chq", "cheque", "cash");

  /** Plain words and the system category they mean; money in or out as noted. */
  private record Word(String word, String key, String direction) {}

  private static final List<Word> WORDS = List.of(
      new Word("salary", "salary", "in"), new Word("payroll", "salary", "in"),
      new Word("rent", "rent", "out"),
      new Word("grocery", "groceries", "out"), new Word("groceries", "groceries", "out"),
      new Word("supermarket", "groceries", "out"), new Word("kirana", "groceries", "out"),
      new Word("electricity", "bills", "out"), new Word("broadband", "bills", "out"),
      new Word("recharge", "bills", "out"), new Word("postpaid", "bills", "out"),
      new Word("billpay", "bills", "out"), new Word("utility", "bills", "out"),
      new Word("hospital", "medical", "out"), new Word("pharmacy", "medical", "out"),
      new Word("medical", "medical", "out"), new Word("clinic", "medical", "out"),
      new Word("chemist", "medical", "out"), new Word("diagnostics", "medical", "out"),
      new Word("school", "school", "out"), new Word("tuition", "school", "out"),
      new Word("fuel", "transport", "out"), new Word("petrol", "transport", "out"),
      new Word("diesel", "transport", "out"), new Word("fastag", "transport", "out"),
      new Word("toll", "transport", "out"), new Word("railway", "transport", "out"),
      new Word("hotel", "travel", "out"), new Word("airline", "travel", "out"),
      new Word("airlines", "travel", "out"), new Word("flight", "travel", "out"),
      new Word("sip", "investing", "out"), new Word("ppf", "investing", "out"));

  private record Rule(Set<String> tokens, UUID categoryId) {}

  private final List<Rule> rules = new ArrayList<>();
  private final Map<String, UUID> system;

  /** {@code rules} are the owner's fin_category_rule rows; {@code system} maps system keys to ids. */
  Categoriser(List<ObjectNode> rules, Map<String, UUID> system) {
    for (ObjectNode r : rules) {
      Set<String> t = new LinkedHashSet<>(Arrays.asList(r.path("matchText").asText().split(" ")));
      t.remove("");
      if (!t.isEmpty()) this.rules.add(new Rule(t, UUID.fromString(r.path("categoryId").asText())));
    }
    this.rules.sort((a, b) -> b.tokens().size() - a.tokens().size());
    this.system = system;
  }

  /** The category for a row, or null when nothing says. */
  UUID categorise(String description, String direction) {
    List<String> tokens = tokens(description);
    if (tokens.isEmpty()) return null;
    Set<String> have = Set.copyOf(tokens);
    for (Rule r : rules) if (have.containsAll(r.tokens())) return r.categoryId();
    for (Word w : WORDS) {
      if (w.direction().equals(direction) && have.contains(w.word())) {
        UUID id = system.get(w.key());
        if (id != null) return id;
      }
    }
    return null;
  }

  /** The words of a description that name who or what was paid, without references and numbers. */
  static List<String> tokens(String description) {
    if (description == null) return List.of();
    List<String> out = new ArrayList<>();
    for (String t : description.toLowerCase(Locale.ROOT).replaceAll("[^a-z]+", " ").strip().split(" ")) {
      if (t.length() >= 3 && !NOISE.contains(t) && !out.contains(t)) out.add(t);
    }
    return out;
  }

  /** What a rule made from this description matches: its first three telling words. */
  static String matchText(String description) {
    List<String> t = tokens(description);
    String s = String.join(" ", t.subList(0, Math.min(3, t.size())));
    return s.length() > 120 ? s.substring(0, 120) : s;
  }
}
