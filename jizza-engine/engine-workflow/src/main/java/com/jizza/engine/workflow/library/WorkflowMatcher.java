package com.jizza.engine.workflow.library;

import com.jizza.engine.api.util.FuzzyText;
import com.jizza.engine.api.workflow.library.ReuseProfile;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * How well a request fits a saved workflow, by the words they share.
 *
 * <p>Deliberately plain and deterministic: the same request against the same library always gives
 * the same answer, and the answer can be explained (the shared words come back with the score).
 * Words meet when they are near each other ({@link FuzzyText#near}), so "book ramayana movie"
 * meets "Ramayanam Ticket Booking". In a chat Jizza sees the candidates and decides; a scheduled
 * task only takes a match well above {@link #STRONG}, so a loose overlap never runs something on
 * its own.
 */
public final class WorkflowMatcher {

  /** A candidate worth putting in front of Jizza. */
  public static final double PLAUSIBLE = 0.25;
  /** Close enough for a scheduled task to run it without anyone choosing. */
  public static final double STRONG = 0.6;

  private WorkflowMatcher() {}

  public record Score(double value, List<String> shared) {}

  public static Score score(String request, String name, String description, ReuseProfile profile) {
    return score(request, name, description, profile, null);
  }

  /**
   * @param steps what the workflow's steps say about themselves (labels, sites), which counts
   *              toward the words the request shares but not toward the name
   */
  public static Score score(String request, String name, String description, ReuseProfile profile,
                            String steps) {
    Map<String, String> asked = terms(request);
    if (asked.isEmpty()) return new Score(0, List.of());

    Map<String, String> named = terms(name);
    Set<String> everything = new LinkedHashSet<>(named.keySet());
    everything.addAll(terms(description).keySet());
    everything.addAll(terms(steps).keySet());
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
      if (FuzzyText.nearAny(stem, everything)) shared.add(word);
    });
    double coverage = (double) shared.size() / asked.size();
    double nameCovered = named.isEmpty() ? 0
        : (double) named.keySet().stream()
            .filter(n -> FuzzyText.nearAny(n, asked.keySet())).count() / named.size();

    double value = 0.45 * bestExample + 0.35 * coverage + 0.20 * nameCovered;
    return new Score(Math.round(value * 1000) / 1000.0, shared);
  }

  /** Stem to the first word that produced it, in order. */
  static Map<String, String> terms(String text) {
    return FuzzyText.terms(text);
  }

  /** Enough stemming that "sends" and "sending" meet "send"; no more. */
  static String stem(String word) {
    return FuzzyText.stem(word);
  }

  /** Overlap of two word sets, counting near words as shared. */
  private static double jaccard(Set<String> a, Set<String> b) {
    if (a.isEmpty() || b.isEmpty()) return 0;
    long both = a.stream().filter(s -> FuzzyText.nearAny(s, b)).count();
    return Math.min(1.0, (double) both / Math.max(a.size(), a.size() + b.size() - both));
  }
}
