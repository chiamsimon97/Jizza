package com.jizza.engine.workflow.library;

import com.jizza.engine.api.workflow.library.ReuseProfile;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * How well a request fits a saved workflow, by the words they share.
 *
 * <p>Deliberately plain and deterministic: the same request against the same library always gives
 * the same answer, and the answer can be explained (the shared words come back with the score). In
 * a chat Jizza sees the candidates and decides; a scheduled task only takes a match well above
 * {@link #STRONG}, so a loose overlap never runs something on its own.
 */
public final class WorkflowMatcher {

  /** A candidate worth putting in front of Jizza. */
  public static final double PLAUSIBLE = 0.25;
  /** Close enough for a scheduled task to run it without anyone choosing. */
  public static final double STRONG = 0.6;

  private static final Set<String> STOPWORDS = Set.of(
      "a", "an", "and", "are", "as", "at", "be", "by", "can", "could", "do", "for", "from", "get",
      "give", "go", "have", "i", "if", "in", "is", "it", "its", "just", "let", "me", "my", "need",
      "of", "on", "or", "our", "please", "so", "some", "that", "the", "their", "them", "then",
      "there", "this", "to", "up", "us", "want", "was", "we", "what", "when", "which", "will",
      "with", "would", "you", "your", "jizza", "now", "again", "also", "make", "sure");

  private WorkflowMatcher() {}

  public record Score(double value, List<String> shared) {}

  public static Score score(String request, String name, String description, ReuseProfile profile) {
    Map<String, String> asked = terms(request);
    if (asked.isEmpty()) return new Score(0, List.of());

    Map<String, String> named = terms(name);
    Set<String> everything = new LinkedHashSet<>(named.keySet());
    everything.addAll(terms(description).keySet());
    double bestExample = 0;
    if (profile != null) {
      everything.addAll(terms(profile.summary()).keySet());
      for (String example : profile.examples()) {
        Set<String> said = terms(example).keySet();
        everything.addAll(said);
        bestExample = Math.max(bestExample, jaccard(asked.keySet(), said));
      }
      for (ReuseProfile.Input input : profile.inputs()) everything.addAll(terms(input.name()).keySet());
    }

    List<String> shared = new ArrayList<>();
    asked.forEach((stem, word) -> {
      if (everything.contains(stem)) shared.add(word);
    });
    double coverage = (double) shared.size() / asked.size();
    double nameCovered = named.isEmpty() ? 0
        : (double) named.keySet().stream().filter(asked::containsKey).count() / named.size();

    double value = 0.45 * bestExample + 0.35 * coverage + 0.20 * nameCovered;
    return new Score(Math.round(value * 1000) / 1000.0, shared);
  }

  /** Stem to the first word that produced it, in order. */
  static Map<String, String> terms(String text) {
    Map<String, String> out = new LinkedHashMap<>();
    if (text == null || text.isBlank()) return out;
    for (String raw : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
      if (raw.length() < 2 || STOPWORDS.contains(raw)) continue;
      out.putIfAbsent(stem(raw), raw);
    }
    return out;
  }

  /** Enough stemming that "sends" and "sending" meet "send"; no more. */
  static String stem(String word) {
    if (word.length() > 5 && word.endsWith("ing")) return trimDouble(word.substring(0, word.length() - 3));
    if (word.length() > 4 && word.endsWith("ies")) return word.substring(0, word.length() - 3) + "y";
    if (word.length() > 4 && word.endsWith("ed")) return trimDouble(word.substring(0, word.length() - 2));
    if (word.length() > 4 && word.endsWith("es") && "sxz".indexOf(word.charAt(word.length() - 3)) >= 0) {
      return word.substring(0, word.length() - 2);
    }
    if (word.length() > 3 && word.endsWith("s") && !word.endsWith("ss")) {
      return word.substring(0, word.length() - 1);
    }
    return word;
  }

  private static String trimDouble(String stem) {
    int n = stem.length();
    return n > 2 && stem.charAt(n - 1) == stem.charAt(n - 2) ? stem.substring(0, n - 1) : stem;
  }

  private static double jaccard(Set<String> a, Set<String> b) {
    if (a.isEmpty() || b.isEmpty()) return 0;
    long both = a.stream().filter(b::contains).count();
    return (double) both / (a.size() + b.size() - both);
  }
}
