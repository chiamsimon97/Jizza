package com.jizza.engine.memory.retrieval;

import com.jizza.engine.persistence.entity.MemorySubjectEntity;
import com.jizza.engine.persistence.entity.MemorySubjectRelationEntity;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Works out who or what a request is about.
 *
 * <p>A subject is named in a request by its name, one of its aliases, or its relation to the user
 * ("my wife", "my wife's"), matched as whole words. A person is also recognised by their first
 * name. From the subjects named, relations are followed outward -- Priya leads to her investment
 * plan and to the account it is held in -- so a request about her picks up what hangs off her.
 *
 * <p>Pure and side-effect free: it is handed the subjects and relations the reader may see.
 */
public final class SubjectResolver {

  private SubjectResolver() {}

  /** Subjects named in the request, and those reached from them by following relations. */
  public record Resolution(Set<UUID> direct, Set<UUID> related) {
    public static final Resolution NONE = new Resolution(Set.of(), Set.of());

    public boolean isEmpty() { return direct.isEmpty() && related.isEmpty(); }
  }

  public static Resolution resolve(String request, Collection<MemorySubjectEntity> visible,
                                   Collection<MemorySubjectRelationEntity> edges, int depth) {
    if (request == null || request.isBlank() || visible.isEmpty()) return Resolution.NONE;
    String text = " " + request.toLowerCase(Locale.ROOT) + " ";

    Set<UUID> direct = new LinkedHashSet<>();
    for (MemorySubjectEntity subject : visible) {
      for (String phrase : phrases(subject)) {
        if (mentions(text, phrase)) {
          direct.add(subject.getId());
          break;
        }
      }
    }
    if (direct.isEmpty()) return Resolution.NONE;

    Set<UUID> visibleIds = new HashSet<>();
    visible.forEach(s -> visibleIds.add(s.getId()));
    Map<UUID, List<UUID>> neighbours = new LinkedHashMap<>();
    for (MemorySubjectRelationEntity edge : edges) {
      if (!visibleIds.contains(edge.getFromSubjectId()) || !visibleIds.contains(edge.getToSubjectId())) {
        continue;
      }
      neighbours.computeIfAbsent(edge.getFromSubjectId(), k -> new ArrayList<>()).add(edge.getToSubjectId());
      neighbours.computeIfAbsent(edge.getToSubjectId(), k -> new ArrayList<>()).add(edge.getFromSubjectId());
    }

    Set<UUID> related = new LinkedHashSet<>();
    Set<UUID> frontier = new LinkedHashSet<>(direct);
    for (int hop = 0; hop < Math.max(0, depth); hop++) {
      Set<UUID> next = new LinkedHashSet<>();
      for (UUID id : frontier) {
        for (UUID n : neighbours.getOrDefault(id, List.of())) {
          if (!direct.contains(n) && related.add(n)) next.add(n);
        }
      }
      frontier = next;
    }
    return new Resolution(direct, related);
  }

  /** The phrases that mean this subject. */
  static List<String> phrases(MemorySubjectEntity subject) {
    List<String> phrases = new ArrayList<>();
    add(phrases, subject.getName());
    for (String alias : subject.getAliases()) add(phrases, alias);
    add(phrases, subject.getRelation());
    if (subject.getKind() == com.jizza.engine.api.enums.SubjectKind.PERSON && subject.getName() != null) {
      String first = subject.getName().strip().split("\\s+")[0];
      if (first.length() >= 3) add(phrases, first);
    }
    return phrases;
  }

  private static void add(List<String> phrases, String phrase) {
    if (phrase == null) return;
    String p = phrase.strip().toLowerCase(Locale.ROOT).replace('_', ' ');
    if (p.length() >= 2 && !phrases.contains(p)) phrases.add(p);
  }

  /** Whole-word match, so "ann" does not match "annual" while "wife" still matches "wife's". */
  static boolean mentions(String text, String phrase) {
    return Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(phrase) + "(?![\\p{L}\\p{N}])")
        .matcher(text).find();
  }
}
