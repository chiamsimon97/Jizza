package com.jizza.platform.usecase.usage;

import com.jizza.engine.api.dto.UsageEntryDto;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

/** Shapes for the usage view: spend over a time range, grouped two levels deep. */
public final class UsageExplorer {

  private UsageExplorer() {}

  /** What usage can be grouped by. {@code AREA} is the part of the purpose before the colon. */
  public enum Dimension { MODEL, AREA, PURPOSE, DAY }

  /**
   * @param groupBy  the outer grouping
   * @param thenBy   the inner grouping; null for none
   * @param models   only these models; empty for all
   * @param areas    only these areas; empty for all
   * @param zone     the viewer's time zone, for days
   */
  public record Query(Instant from, Instant to, Dimension groupBy, Dimension thenBy,
                      Set<String> models, Set<String> areas, int limit, ZoneId zone) {

    public Query {
      models = models == null ? Set.of() : Set.copyOf(models);
      areas = areas == null ? Set.of() : Set.copyOf(areas);
      if (groupBy == null) groupBy = Dimension.MODEL;
      if (zone == null) zone = java.time.ZoneOffset.UTC;
    }
  }

  public record Totals(long calls, long inputTokens, long outputTokens, BigDecimal costUnits) {}

  public record Group(String key, String label, Totals totals, List<Group> children) {}

  public record Bucket(Instant start, long calls, long tokens, BigDecimal costUnits) {}

  /**
   * @param models    every model in the range, for the filter
   * @param areas     every area in the range, for the filter
   * @param truncated true when the range held more records than are scanned
   */
  public record Page(Totals totals, List<Group> groups, String bucketUnit, List<Bucket> buckets,
                     List<UsageEntryDto> entries, List<String> models, List<String> areas,
                     boolean truncated) {}
}
