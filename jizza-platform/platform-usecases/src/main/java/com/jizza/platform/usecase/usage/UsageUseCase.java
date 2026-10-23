package com.jizza.platform.usecase.usage;

import com.jizza.engine.api.dto.UsageEntryDto;
import com.jizza.engine.api.llm.UsageStore;
import com.jizza.engine.api.util.TimeBuckets;
import com.jizza.platform.usecase.usage.UsageExplorer.Bucket;
import com.jizza.platform.usecase.usage.UsageExplorer.Dimension;
import com.jizza.platform.usecase.usage.UsageExplorer.Group;
import com.jizza.platform.usecase.usage.UsageExplorer.Page;
import com.jizza.platform.usecase.usage.UsageExplorer.Query;
import com.jizza.platform.usecase.usage.UsageExplorer.Totals;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** Exposes LLM cost and token usage to the user (R49). */
@Service
public class UsageUseCase {

  private static final int MAX_ENTRIES = 200;
  static final int MAX_SCAN = 20_000;

  private final UsageStore store;

  public UsageUseCase(UsageStore store) {
    this.store = store;
  }

  public UsageStore.Summary summary(UUID userId) {
    return store.summary(userId);
  }

  public List<UsageEntryDto> recent(UUID userId, int limit) {
    return store.recent(userId, Math.max(1, Math.min(limit, MAX_ENTRIES)));
  }

  /** Spend in a range, grouped by one dimension and optionally a second inside it. */
  public Page explore(UUID userId, Query query) {
    Instant now = Instant.now();
    List<UsageEntryDto> scanned = store.between(userId, query.from(), query.to(), MAX_SCAN + 1);
    boolean truncated = scanned.size() > MAX_SCAN;
    if (truncated) scanned = scanned.subList(0, MAX_SCAN);

    List<String> models = scanned.stream().map(UsageEntryDto::model).distinct().sorted().toList();
    List<String> areas = scanned.stream().map(e -> areaOf(e.purpose())).distinct().sorted().toList();

    List<UsageEntryDto> matched = scanned.stream()
        .filter(e -> query.models().isEmpty() || query.models().contains(e.model()))
        .filter(e -> query.areas().isEmpty() || query.areas().contains(areaOf(e.purpose())))
        .toList();

    Instant from = query.from() != null ? query.from()
        : matched.isEmpty() ? now.minus(Duration.ofDays(1))
        : matched.get(matched.size() - 1).createdAt();
    Instant to = query.to() == null ? now : query.to();
    TimeBuckets slices = TimeBuckets.covering(from, to, query.zone());

    int limit = Math.max(1, Math.min(query.limit() <= 0 ? 100 : query.limit(), MAX_ENTRIES));
    return new Page(totals(matched),
        group(matched, query.groupBy(), query.thenBy(), query.zone()), slices.unitName(),
        buckets(matched, slices), matched.stream().limit(limit).toList(), models, areas,
        truncated);
  }

  private static List<Group> group(List<UsageEntryDto> entries, Dimension by, Dimension then,
                                   ZoneId zone) {
    Function<UsageEntryDto, String> key = keyOf(by, zone);
    Map<String, List<UsageEntryDto>> grouped = entries.stream()
        .collect(Collectors.groupingBy(key, LinkedHashMap::new, Collectors.toList()));
    Comparator<Group> order = by == Dimension.DAY
        ? Comparator.comparing(Group::key).reversed()
        : Comparator.comparing((Group g) -> g.totals().costUnits()).reversed()
            .thenComparing(Group::key);
    return grouped.entrySet().stream()
        .map(e -> new Group(e.getKey(), labelOf(by, e.getKey()), totals(e.getValue()),
            then == null || then == by ? List.of() : group(e.getValue(), then, null, zone)))
        .sorted(order)
        .toList();
  }

  private static Function<UsageEntryDto, String> keyOf(Dimension dimension, ZoneId zone) {
    return switch (dimension) {
      case MODEL -> UsageEntryDto::model;
      case AREA -> e -> areaOf(e.purpose());
      case PURPOSE -> e -> e.purpose() == null || e.purpose().isBlank() ? "unspecified" : e.purpose();
      case DAY -> e -> e.createdAt().atZone(zone).toLocalDate().toString();
    };
  }

  private static String labelOf(Dimension dimension, String key) {
    return switch (dimension) {
      case AREA -> switch (key) {
        case "agent" -> "Agents";
        case "jizza" -> "Jizza";
        case "chat" -> "Chat";
        case "memory" -> "Memory";
        case "workflow" -> "Workflows";
        default -> Character.toUpperCase(key.charAt(0)) + key.substring(1);
      };
      case PURPOSE -> {
        int colon = key.indexOf(':');
        yield colon < 0 ? key : key.substring(colon + 1) + " · " + key.substring(0, colon);
      }
      default -> key;
    };
  }

  /** {@code "agent:researcher"} is in area {@code "agent"}; a purpose with no colon is its own. */
  static String areaOf(String purpose) {
    if (purpose == null || purpose.isBlank()) return "other";
    int colon = purpose.indexOf(':');
    return (colon < 0 ? purpose : purpose.substring(0, colon)).toLowerCase(Locale.ROOT);
  }

  private static Totals totals(List<UsageEntryDto> entries) {
    long in = entries.stream().mapToLong(UsageEntryDto::inputTokens).sum();
    long out = entries.stream().mapToLong(UsageEntryDto::outputTokens).sum();
    BigDecimal cost = entries.stream().map(UsageEntryDto::costUnits)
        .filter(java.util.Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
    return new Totals(entries.size(), in, out, cost);
  }

  private static List<Bucket> buckets(List<UsageEntryDto> entries, TimeBuckets slices) {
    List<List<UsageEntryDto>> slots = new ArrayList<>();
    slices.starts().forEach(at -> slots.add(new ArrayList<>()));
    for (UsageEntryDto e : entries) {
      int at = slices.indexOf(e.createdAt());
      if (at >= 0) slots.get(at).add(e);
    }
    List<Bucket> out = new ArrayList<>();
    for (int i = 0; i < slots.size(); i++) {
      Totals t = totals(slots.get(i));
      out.add(new Bucket(slices.starts().get(i), t.calls(), t.inputTokens() + t.outputTokens(),
          t.costUnits()));
    }
    return out;
  }
}
