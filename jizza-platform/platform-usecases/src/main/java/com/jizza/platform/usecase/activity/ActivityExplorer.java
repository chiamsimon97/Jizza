package com.jizza.platform.usecase.activity;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.audit.catalog.ActivityLogType.Severity;
import com.jizza.engine.api.enums.ActorType;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Shapes for the grouped, filterable activity view. */
public final class ActivityExplorer {

  private ActivityExplorer() {}

  /**
   * @param from       inclusive; null for no lower bound
   * @param to         exclusive; null for now
   * @param categories category keys to keep; empty for all
   * @param groups     {@code category/group} keys to keep; empty for all in the kept categories
   * @param severities severities to keep; empty for all
   * @param actors     actor types to keep; empty for all
   * @param search     text matched against the label, action, target and actor
   * @param zone       the viewer's time zone, for daily timeline slices
   */
  public record Query(Instant from, Instant to, Set<String> categories, Set<String> groups,
                      Set<Severity> severities, Set<ActorType> actors, String search,
                      int limit, int offset, ZoneId zone) {

    public Query {
      categories = categories == null ? Set.of() : Set.copyOf(categories);
      groups = groups == null ? Set.of() : Set.copyOf(groups);
      severities = severities == null ? Set.of() : Set.copyOf(severities);
      actors = actors == null ? Set.of() : Set.copyOf(actors);
    }
  }

  /** One audit entry, filed and labelled. */
  public record Item(UUID id, Instant createdAt, String action, String label, String category,
                     String group, Severity severity, ActorType actorType, UUID actorId,
                     String actorName, String target, JsonNode detail, String requestId,
                     String hash) {}

  public record GroupCount(String key, String label, long count) {}

  public record CategoryCount(String key, String label, String icon, long count,
                              List<GroupCount> groups) {}

  /** Entries in one slice of the timeline, by category. */
  public record Bucket(Instant start, long total, Map<String, Long> byCategory) {}

  /**
   * @param categories every category with its count under the other filters, so picking one
   *                   still shows what the rest hold
   * @param truncated  true when the range held more entries than are scanned
   */
  public record Page(List<Item> items, long total, List<CategoryCount> categories,
                     Map<Severity, Long> severities, Map<ActorType, Long> actors,
                     String bucketUnit, List<Bucket> buckets, boolean truncated) {}

  /** The catalog the filter panel is drawn from. */
  public record Catalog(List<CategoryCount> categories, List<Severity> severities,
                        List<ActorType> actors) {}
}
