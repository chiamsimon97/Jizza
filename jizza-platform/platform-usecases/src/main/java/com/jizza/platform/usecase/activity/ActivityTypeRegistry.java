package com.jizza.platform.usecase.activity;

import com.jizza.engine.api.audit.catalog.ActivityCategory;
import com.jizza.engine.api.audit.catalog.ActivityCategory.ActivityGroup;
import com.jizza.engine.api.audit.catalog.ActivityLogCatalog;
import com.jizza.engine.api.audit.catalog.ActivityLogType;
import com.jizza.engine.api.audit.catalog.ActivityLogType.Severity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Every {@link ActivityLogCatalog} merged into one lookup: which category, sub-group, label and
 * severity an audit action has. Resolution goes exact type, then the longest matching
 * {@code area.*} type, then the category whose prefix fits, then "Other" -- so an action nobody
 * described is still shown, filed as well as its name allows.
 */
@Component
public class ActivityTypeRegistry {

  public static final String OTHER = "other";
  static final ActivityGroup OTHER_GROUP = new ActivityGroup(OTHER, "Other");

  /** Where an action is filed and how it reads. */
  public record Classified(String category, String group, String label, Severity severity) {}

  private final List<ActivityCategory> categories;
  private final Map<String, ActivityCategory> categoriesByKey;
  private final Map<String, ActivityLogType> exact = new HashMap<>();
  private final List<ActivityLogType> prefixes = new ArrayList<>();
  private final Map<String, Classified> cache = new ConcurrentHashMap<>();

  public ActivityTypeRegistry(List<ActivityLogCatalog> catalogs) {
    Map<String, ActivityCategory> merged = new LinkedHashMap<>();
    for (ActivityLogCatalog catalog : catalogs) {
      for (ActivityCategory c : catalog.categories()) merged.merge(c.key(), c, ActivityTypeRegistry::merge);
    }
    merged.putIfAbsent(OTHER, new ActivityCategory(OTHER, "Other", "Circle", Integer.MAX_VALUE,
        List.of(), List.of(OTHER_GROUP)));
    for (ActivityLogCatalog catalog : catalogs) {
      for (ActivityLogType t : catalog.types()) {
        if (t.isPrefix()) prefixes.add(t);
        else exact.putIfAbsent(t.pattern(), t);
      }
    }
    prefixes.sort(Comparator.comparingInt((ActivityLogType t) -> t.pattern().length()).reversed());
    this.categories = merged.values().stream()
        .sorted(Comparator.comparingInt(ActivityCategory::order)).toList();
    this.categoriesByKey = new LinkedHashMap<>();
    categories.forEach(c -> categoriesByKey.put(c.key(), c));
  }

  /** The categories in display order, each with its sub-groups. */
  public List<ActivityCategory> categories() {
    return categories;
  }

  public Optional<ActivityCategory> category(String key) {
    return Optional.ofNullable(categoriesByKey.get(key));
  }

  public Classified classify(String action) {
    if (action == null || action.isBlank()) {
      return new Classified(OTHER, OTHER, "Unnamed event", Severity.INFO);
    }
    return cache.computeIfAbsent(action, this::resolve);
  }

  private Classified resolve(String action) {
    ActivityLogType type = exact.get(action);
    if (type == null) {
      type = prefixes.stream().filter(t -> t.matches(action)).findFirst().orElse(null);
    }
    if (type != null && categoriesByKey.containsKey(type.category())) {
      return new Classified(type.category(), groupOrOther(type.category(), type.group()),
          type.label() == null ? humanize(action) : type.label(),
          type.severity() == null ? Severity.INFO : type.severity());
    }
    String category = categories.stream()
        .filter(c -> c.prefixes().stream().anyMatch(action::startsWith))
        .map(ActivityCategory::key).findFirst().orElse(OTHER);
    return new Classified(category, OTHER, humanize(action), Severity.INFO);
  }

  private String groupOrOther(String category, String group) {
    ActivityCategory c = categoriesByKey.get(category);
    return c.groups().stream().anyMatch(g -> g.key().equals(group)) ? group : OTHER;
  }

  /** {@code "connection.token_refreshed"} reads as "Connection token refreshed". */
  static String humanize(String action) {
    String words = action.replace('.', ' ').replace('_', ' ').replace('-', ' ')
        .replaceAll("\\s+", " ").strip().toLowerCase(Locale.ROOT);
    return words.isEmpty() ? action
        : Character.toUpperCase(words.charAt(0)) + words.substring(1);
  }

  private static ActivityCategory merge(ActivityCategory first, ActivityCategory more) {
    List<String> prefixes = new ArrayList<>(first.prefixes());
    more.prefixes().stream().filter(p -> !prefixes.contains(p)).forEach(prefixes::add);
    List<ActivityGroup> groups = new ArrayList<>(first.groups());
    more.groups().stream()
        .filter(g -> groups.stream().noneMatch(existing -> existing.key().equals(g.key())))
        .forEach(groups::add);
    return new ActivityCategory(first.key(), first.label(), first.icon(), first.order(), prefixes,
        groups);
  }
}
