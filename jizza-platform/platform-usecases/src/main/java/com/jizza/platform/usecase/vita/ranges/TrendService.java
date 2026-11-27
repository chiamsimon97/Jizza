package com.jizza.platform.usecase.vita.ranges;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;

/**
 * Trends of one kind of reading (spec 5.5). Pure functions over plain points: last value, averages,
 * min and max, how many readings were outside range, and for blood pressure the morning versus
 * evening split (before 12:00 in the person's zone is morning). Two readings joined by a pair id
 * count once, as their average. Averages are kept unrounded; {@link Stat#shown} rounds half up
 * for display only.
 */
public final class TrendService {

  public static final LocalTime NOON = LocalTime.NOON;

  private TrendService() {}

  /** One reading; {@code status} is the evaluator's status for it, or null. */
  public record Point(UUID id, Instant takenAt, BigDecimal value, BigDecimal value2, UUID pairId, String status) {}

  /** Sum, count and the unrounded mean of one series. */
  public record Stat(BigDecimal sum, int count, BigDecimal mean, BigDecimal shown, BigDecimal min, BigDecimal max) {

    static Stat of(List<BigDecimal> values, int decimals) {
      if (values.isEmpty()) return null;
      BigDecimal sum = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
      BigDecimal mean = sum.divide(BigDecimal.valueOf(values.size()), 10, RoundingMode.HALF_UP);
      return new Stat(sum, values.size(), mean, mean.setScale(decimals, RoundingMode.HALF_UP),
          values.stream().min(Comparator.naturalOrder()).orElseThrow(),
          values.stream().max(Comparator.naturalOrder()).orElseThrow());
    }
  }

  /** One side of a series (all, morning or evening): first value and, for BP, the second. */
  public record Side(Stat value, Stat value2, int count, long outside) {}

  /** One local day of the diary: the morning and evening readings, each with its status. */
  public record Day(LocalDate date, List<Point> morning, List<Point> evening) {}

  public record Trend(String kind, int count, Point last, Side all, Side morning, Side evening,
                      List<Day> days) {}

  /** Decimals a kind is shown with. */
  public static int decimals(String kind) {
    return switch (kind) {
      case "weight", "temperature" -> 1;
      default -> 0;
    };
  }

  /**
   * Joins paired readings into one point: the average of the pair, at the first reading's time,
   * with the worse status of the two.
   */
  public static List<Point> collapsePairs(List<Point> points) {
    Map<Object, List<Point>> groups = new LinkedHashMap<>();
    for (Point p : points) groups.computeIfAbsent(p.pairId() != null ? p.pairId() : p.id(), k -> new ArrayList<>()).add(p);
    List<Point> out = new ArrayList<>();
    for (List<Point> g : groups.values()) {
      if (g.size() == 1) { out.add(g.get(0)); continue; }
      g.sort(Comparator.comparing(Point::takenAt));
      out.add(new Point(g.get(0).id(), g.get(0).takenAt(), avg(g, Point::value), avg(g, Point::value2),
          g.get(0).pairId(), worst(g)));
    }
    out.sort(Comparator.comparing(Point::takenAt));
    return out;
  }

  /** The trend of the points given (already narrowed to a period). */
  public static Trend trend(String kind, List<Point> points, ZoneId zone) {
    List<Point> pts = collapsePairs(points);
    int d = decimals(kind);
    List<Point> morning = new ArrayList<>();
    List<Point> evening = new ArrayList<>();
    TreeMap<LocalDate, Day> days = new TreeMap<>();
    for (Point p : pts) {
      var local = p.takenAt().atZone(zone);
      boolean am = local.toLocalTime().isBefore(NOON);
      (am ? morning : evening).add(p);
      Day day = days.computeIfAbsent(local.toLocalDate(), k -> new Day(k, new ArrayList<>(), new ArrayList<>()));
      (am ? day.morning() : day.evening()).add(p);
    }
    Point last = pts.isEmpty() ? null : pts.get(pts.size() - 1);
    return new Trend(kind, pts.size(), last, side(pts, d), side(morning, d), side(evening, d),
        List.copyOf(days.values()));
  }

  static Side side(List<Point> pts, int decimals) {
    List<BigDecimal> v = pts.stream().map(Point::value).filter(java.util.Objects::nonNull).toList();
    List<BigDecimal> v2 = pts.stream().map(Point::value2).filter(java.util.Objects::nonNull).toList();
    long outside = pts.stream().filter(p -> TargetEvaluator.OUTSIDE_RANGE.equals(p.status())
        || TargetEvaluator.RED_FLAG.equals(p.status())).count();
    return new Side(Stat.of(v, decimals), Stat.of(v2, decimals), pts.size(), outside);
  }

  private static BigDecimal avg(List<Point> g, Function<Point, BigDecimal> f) {
    List<BigDecimal> v = g.stream().map(f).filter(java.util.Objects::nonNull).toList();
    if (v.isEmpty()) return null;
    return v.stream().reduce(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal.valueOf(v.size()), 10, RoundingMode.HALF_UP);
  }

  private static String worst(List<Point> g) {
    List<String> order = List.of(TargetEvaluator.RED_FLAG, TargetEvaluator.OUTSIDE_RANGE,
        TargetEvaluator.IN_RANGE, TargetEvaluator.NOT_SET);
    return g.stream().map(Point::status).filter(java.util.Objects::nonNull)
        .min(Comparator.comparingInt(s -> order.indexOf(s) < 0 ? 99 : order.indexOf(s))).orElse(null);
  }
}
