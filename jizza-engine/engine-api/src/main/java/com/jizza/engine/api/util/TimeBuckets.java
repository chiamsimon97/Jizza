package com.jizza.engine.api.util;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Slices a time range into hours or days for the timelines on the history views. Days follow the
 * viewer's time zone, so "Tuesday" means their Tuesday.
 */
public final class TimeBuckets {

  /** Above this many slices the range is cut, oldest first; a year of days fits. */
  public static final int MAX_SLOTS = 400;

  private final ChronoUnit unit;
  private final ZoneId zone;
  private final List<Instant> starts;

  private TimeBuckets(ChronoUnit unit, ZoneId zone, List<Instant> starts) {
    this.unit = unit;
    this.zone = zone;
    this.starts = starts;
  }

  /** Hours for a range of three days or less, days beyond that. */
  public static TimeBuckets covering(Instant from, Instant to, ZoneId zone) {
    ZoneId z = zone == null ? ZoneOffset.UTC : zone;
    ChronoUnit unit = Duration.between(from, to).toHours() <= 72 ? ChronoUnit.HOURS : ChronoUnit.DAYS;
    List<Instant> starts = new ArrayList<>();
    Instant at = truncate(from, unit, z);
    while (at.isBefore(to) && starts.size() < MAX_SLOTS) {
      starts.add(at);
      at = next(at, unit, z);
    }
    if (starts.isEmpty()) starts.add(truncate(from, unit, z));
    return new TimeBuckets(unit, z, starts);
  }

  public String unitName() {
    return unit == ChronoUnit.HOURS ? "hour" : "day";
  }

  public List<Instant> starts() {
    return starts;
  }

  /** Index of the slice {@code at} falls in, or -1 outside the range. */
  public int indexOf(Instant at) {
    if (at == null) return -1;
    Instant start = truncate(at, unit, zone);
    int lo = 0;
    int hi = starts.size() - 1;
    while (lo <= hi) {
      int mid = (lo + hi) >>> 1;
      int cmp = starts.get(mid).compareTo(start);
      if (cmp == 0) return mid;
      if (cmp < 0) lo = mid + 1;
      else hi = mid - 1;
    }
    return -1;
  }

  private static Instant truncate(Instant at, ChronoUnit unit, ZoneId zone) {
    return unit == ChronoUnit.HOURS ? at.truncatedTo(ChronoUnit.HOURS)
        : at.atZone(zone).truncatedTo(ChronoUnit.DAYS).toInstant();
  }

  private static Instant next(Instant at, ChronoUnit unit, ZoneId zone) {
    return unit == ChronoUnit.HOURS ? at.plus(1, ChronoUnit.HOURS)
        : at.atZone(zone).plusDays(1).toInstant();
  }

  /** Parses a browser time zone name, falling back to UTC for anything unknown. */
  public static ZoneId zoneOf(String name) {
    if (name == null || name.isBlank()) return ZoneOffset.UTC;
    try {
      return ZoneId.of(name);
    } catch (RuntimeException e) {
      return ZoneOffset.UTC;
    }
  }
}
