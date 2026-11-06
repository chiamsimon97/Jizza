package com.jizza.engine.api.util;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Plain, deterministic word matching that forgives the way people actually refer to things:
 * "book ramayana movie" should meet "Ramayanam Ticket Booking". Words are lower-cased, filler is
 * dropped, light stemming makes "booking" meet "book", and two words also meet when one starts
 * with the other or they are one or two letters apart (a spelling, a transliteration, a typo).
 *
 * <p>Used wherever Jizza looks the user's words up in what they have built (workflows, functions,
 * agents, devices, connections), so the same request finds the same things everywhere.
 */
public final class FuzzyText {

  /** Words that say nothing about which thing the user means. */
  public static final Set<String> STOPWORDS = Set.of(
      "a", "an", "and", "are", "as", "at", "be", "by", "can", "could", "do", "for", "from", "get",
      "give", "go", "have", "i", "if", "in", "is", "it", "its", "just", "let", "me", "my", "need",
      "of", "on", "or", "our", "please", "so", "some", "that", "the", "their", "them", "then",
      "there", "this", "to", "up", "us", "want", "was", "we", "what", "when", "which", "will",
      "with", "would", "you", "your", "jizza", "now", "again", "also", "make", "sure",
      // How people point at a saved thing rather than what it does.
      "run", "runs", "running", "ran", "start", "execute", "trigger", "use", "using", "workflow",
      "workflows", "flow", "flows", "automation", "created", "create", "made", "saved", "save",
      "named", "called", "one", "thing", "something", "help", "show", "tell", "about", "any", "all",
      "did", "done", "earlier", "before", "yes", "ok", "okay", "pls", "kindly", "hey", "hi");

  private FuzzyText() {}

  /** Stem to the first word that produced it, in order. */
  public static Map<String, String> terms(String text) {
    Map<String, String> out = new LinkedHashMap<>();
    if (text == null || text.isBlank()) return out;
    for (String raw : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
      if (raw.length() < 2 || STOPWORDS.contains(raw)) continue;
      out.putIfAbsent(stem(raw), raw);
    }
    return out;
  }

  /** Enough stemming that "sends" and "sending" meet "send"; no more. */
  public static String stem(String word) {
    if (word.length() > 5 && word.endsWith("ing")) return trimDouble(word.substring(0, word.length() - 3));
    if (word.length() > 4 && word.endsWith("ies")) return word.substring(0, word.length() - 3) + "y";
    // So that "movie" meets "movies" (which became "movy" above).
    if (word.length() > 3 && word.endsWith("ie")) return word.substring(0, word.length() - 2) + "y";
    if (word.length() > 4 && word.endsWith("ed")) return trimDouble(word.substring(0, word.length() - 2));
    if (word.length() > 4 && word.endsWith("es") && "sxz".indexOf(word.charAt(word.length() - 3)) >= 0) {
      return word.substring(0, word.length() - 2);
    }
    if (word.length() > 3 && word.endsWith("s") && !word.endsWith("ss")) {
      return word.substring(0, word.length() - 1);
    }
    return word;
  }

  /**
   * Whether two stems name the same word: equal, one starting with the other (at least four
   * letters in common), or close in spelling for longer words.
   */
  public static boolean near(String a, String b) {
    if (a.equals(b)) return true;
    String shorter = a.length() <= b.length() ? a : b;
    String longer = shorter == a ? b : a;
    if (shorter.length() >= 4 && longer.startsWith(shorter)) return true;
    if (shorter.length() < 5) return false;
    int allowed = shorter.length() >= 8 ? 2 : 1;
    if (longer.length() - shorter.length() > allowed) return false;
    return distance(a, b, allowed) <= allowed;
  }

  /** Whether {@code stem} meets any of {@code pool}. */
  public static boolean nearAny(String stem, Collection<String> pool) {
    if (pool.contains(stem)) return true;
    for (String other : pool) if (near(stem, other)) return true;
    return false;
  }

  /**
   * How much of {@code query} the {@code text} covers, 0..1, counting near words. Zero when the
   * query has no meaningful words.
   */
  public static double coverage(String query, String text) {
    Map<String, String> asked = terms(query);
    if (asked.isEmpty()) return 0;
    Set<String> pool = terms(text).keySet();
    long hit = asked.keySet().stream().filter(s -> nearAny(s, pool)).count();
    return (double) hit / asked.size();
  }

  private static String trimDouble(String stem) {
    int n = stem.length();
    return n > 2 && stem.charAt(n - 1) == stem.charAt(n - 2) ? stem.substring(0, n - 1) : stem;
  }

  /** Levenshtein distance, giving up early once it is over {@code limit}. */
  static int distance(String a, String b, int limit) {
    int[] prev = new int[b.length() + 1];
    int[] cur = new int[b.length() + 1];
    for (int j = 0; j <= b.length(); j++) prev[j] = j;
    for (int i = 1; i <= a.length(); i++) {
      cur[0] = i;
      int rowMin = cur[0];
      for (int j = 1; j <= b.length(); j++) {
        int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
        cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
        rowMin = Math.min(rowMin, cur[j]);
      }
      if (rowMin > limit) return limit + 1;
      int[] t = prev;
      prev = cur;
      cur = t;
    }
    return prev[b.length()];
  }
}
