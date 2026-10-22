package com.jizza.platform.usecase.activity;

import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.audit.catalog.ActivityCategory;
import com.jizza.engine.api.audit.catalog.ActivityLogType.Severity;
import com.jizza.engine.api.dto.AuditEntryDto;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.orchestrator.AgentDirectory;
import com.jizza.engine.api.util.TimeBuckets;
import com.jizza.platform.usecase.activity.ActivityExplorer.Bucket;
import com.jizza.platform.usecase.activity.ActivityExplorer.CategoryCount;
import com.jizza.platform.usecase.activity.ActivityExplorer.GroupCount;
import com.jizza.platform.usecase.activity.ActivityExplorer.Item;
import com.jizza.platform.usecase.activity.ActivityExplorer.Page;
import com.jizza.platform.usecase.activity.ActivityExplorer.Query;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import org.springframework.stereotype.Service;

/**
 * The readable activity view over the audit log (R56), plus chain verification. Entries come back
 * from the engine without a resolved actor name -- labelling one as "You", "Jizza", or an agent's
 * name is a presentation call, not something the engine should decide.
 *
 * <p>Where an entry is filed comes from the {@link ActivityTypeRegistry}; this class only filters,
 * counts and pages. A window scans at most {@link #MAX_SCAN} entries, newest first, and says so
 * when it held more.
 */
@Service
public class ActivityUseCase {

  static final int MAX_SCAN = 10_000;
  static final int MAX_PAGE = 200;

  private final AuditLog audit;
  private final AgentDirectory agents;
  private final ActivityTypeRegistry types;

  public ActivityUseCase(AuditLog audit, AgentDirectory agents, ActivityTypeRegistry types) {
    this.audit = audit;
    this.agents = agents;
    this.types = types;
  }

  public List<AuditEntryDto> recent(UUID userId, int limit, String action) {
    return audit.recent(userId, limit, action).stream().map(this::withActorName).toList();
  }

  /** Every category and sub-group the view can filter on, without counts. */
  public ActivityExplorer.Catalog catalog() {
    List<CategoryCount> categories = types.categories().stream()
        .map(c -> new CategoryCount(c.key(), c.label(), c.icon(), 0,
            c.groups().stream().map(g -> new GroupCount(g.key(), g.label(), 0)).toList()))
        .toList();
    return new ActivityExplorer.Catalog(categories, Arrays.asList(Severity.values()),
        Arrays.asList(ActorType.values()));
  }

  public Page explore(UUID userId, Query query) {
    Instant now = Instant.now();
    List<AuditEntryDto> scanned = audit.between(userId, query.from(), query.to(), MAX_SCAN + 1);
    boolean truncated = scanned.size() > MAX_SCAN;
    if (truncated) scanned = scanned.subList(0, MAX_SCAN);

    Map<UUID, String> agentNames = new HashMap<>();
    List<Item> all = scanned.stream().map(e -> item(e, agentNames)).toList();

    Predicate<Item> inCategory = i -> query.categories().isEmpty()
        || query.categories().contains(i.category());
    Predicate<Item> inGroup = i -> query.groups().isEmpty()
        || query.groups().contains(i.category() + "/" + i.group());
    Predicate<Item> inSeverity = i -> query.severities().isEmpty()
        || query.severities().contains(i.severity());
    Predicate<Item> byActor = i -> query.actors().isEmpty()
        || query.actors().contains(i.actorType());
    Predicate<Item> bySearch = searchFilter(query.search());

    // Each facet is counted under every filter but its own.
    List<Item> forCategories = all.stream().filter(inSeverity.and(byActor).and(bySearch)).toList();
    List<Item> matched = forCategories.stream().filter(inCategory.and(inGroup)).toList();
    Map<Severity, Long> severities = new EnumMap<>(Severity.class);
    all.stream().filter(inCategory.and(inGroup).and(byActor).and(bySearch))
        .forEach(i -> severities.merge(i.severity(), 1L, Long::sum));
    Map<ActorType, Long> actors = new EnumMap<>(ActorType.class);
    all.stream().filter(inCategory.and(inGroup).and(inSeverity).and(bySearch))
        .forEach(i -> actors.merge(i.actorType(), 1L, Long::sum));

    int limit = Math.max(1, Math.min(query.limit() <= 0 ? 100 : query.limit(), MAX_PAGE));
    List<Item> page = matched.stream().skip(Math.max(0, query.offset())).limit(limit).toList();

    Instant from = query.from() != null ? query.from()
        : matched.isEmpty() ? now.minus(Duration.ofDays(1))
        : matched.get(matched.size() - 1).createdAt();
    Instant to = query.to() == null ? now : query.to();
    TimeBuckets slices = TimeBuckets.covering(from, to, query.zone());

    return new Page(page, matched.size(), categoryCounts(forCategories), severities, actors,
        slices.unitName(), buckets(matched, slices), truncated);
  }

  /** Recomputes the chain so the user can confirm nothing has been altered or removed (R14). */
  public ChainVerificationResult verify(UUID userId) {
    long count = audit.countForUser(userId);
    return audit.verifyChain(userId)
        .map(broken -> new ChainVerificationResult(false, broken, count))
        .orElseGet(() -> new ChainVerificationResult(true, null, count));
  }

  private Item item(AuditEntryDto e, Map<UUID, String> agentNames) {
    ActivityTypeRegistry.Classified c = types.classify(e.action());
    return new Item(e.id(), e.createdAt(), e.action(), c.label(), c.category(), c.group(),
        c.severity(), e.actorType(), e.actorId(), actorName(e, agentNames), e.target(), e.detail(),
        e.requestId(), e.hash());
  }

  private List<CategoryCount> categoryCounts(List<Item> items) {
    Map<String, Map<String, Long>> counts = new HashMap<>();
    items.forEach(i -> counts.computeIfAbsent(i.category(), k -> new HashMap<>())
        .merge(i.group(), 1L, Long::sum));
    List<CategoryCount> out = new ArrayList<>();
    for (ActivityCategory c : types.categories()) {
      Map<String, Long> byGroup = counts.getOrDefault(c.key(), Map.of());
      List<GroupCount> groups = new ArrayList<>();
      c.groups().forEach(g -> groups.add(new GroupCount(g.key(), g.label(),
          byGroup.getOrDefault(g.key(), 0L))));
      boolean listsOther = c.groups().stream()
          .anyMatch(g -> g.key().equals(ActivityTypeRegistry.OTHER));
      if (!listsOther && byGroup.containsKey(ActivityTypeRegistry.OTHER)) {
        groups.add(new GroupCount(ActivityTypeRegistry.OTHER, "Other",
            byGroup.get(ActivityTypeRegistry.OTHER)));
      }
      long total = byGroup.values().stream().mapToLong(Long::longValue).sum();
      out.add(new CategoryCount(c.key(), c.label(), c.icon(), total, groups));
    }
    return out;
  }

  private static List<Bucket> buckets(List<Item> items, TimeBuckets slices) {
    List<Map<String, Long>> counts = new ArrayList<>();
    slices.starts().forEach(at -> counts.add(new HashMap<>()));
    for (Item item : items) {
      int at = slices.indexOf(item.createdAt());
      if (at >= 0) counts.get(at).merge(item.category(), 1L, Long::sum);
    }
    List<Bucket> out = new ArrayList<>();
    for (int i = 0; i < counts.size(); i++) {
      Map<String, Long> byCategory = counts.get(i);
      out.add(new Bucket(slices.starts().get(i),
          byCategory.values().stream().mapToLong(Long::longValue).sum(), byCategory));
    }
    return out;
  }

  private static Predicate<Item> searchFilter(String search) {
    if (search == null || search.isBlank()) return i -> true;
    String needle = search.strip().toLowerCase(Locale.ROOT);
    return i -> contains(i.label(), needle) || contains(i.action(), needle)
        || contains(i.target(), needle) || contains(i.actorName(), needle);
  }

  private static boolean contains(String haystack, String needle) {
    return haystack != null && haystack.toLowerCase(Locale.ROOT).contains(needle);
  }

  private AuditEntryDto withActorName(AuditEntryDto e) {
    return new AuditEntryDto(e.id(), e.actorType(), e.actorId(), actorName(e, new HashMap<>()),
        e.action(), e.target(), e.detail(), e.requestId(), e.hash(), e.createdAt());
  }

  private String actorName(AuditEntryDto e, Map<UUID, String> agentNames) {
    return switch (e.actorType()) {
      case AGENT -> e.actorId() == null ? "An agent"
          : agentNames.computeIfAbsent(e.actorId(), agents::nameOf);
      case USER -> "You";
      case SYSTEM -> "Jizza";
    };
  }
}
