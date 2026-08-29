package com.jizza.scheduler.recurrence;

import com.jizza.common.error.ValidationException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * The subset of RFC 5545 recurrence that a personal assistant actually needs: {@code FREQ},
 * {@code INTERVAL}, {@code BYDAY}, {@code BYMONTHDAY}, {@code BYMONTH}, {@code BYHOUR},
 * {@code BYMINUTE}, {@code COUNT} and {@code UNTIL}.
 *
 * <p>Everything is computed in the task's own zone as local wall-clock time, so "every weekday at
 * 09:00 IST" stays 09:00 across a DST transition anywhere it is hosted, rather than drifting by an
 * hour twice a year. Only at the end is the result converted back to an instant.
 */
public record Rrule(Freq freq, int interval, Set<DayOfWeek> byDay, Set<Integer> byMonthDay,
                    Set<Integer> byMonth, Set<Integer> byHour, Set<Integer> byMinute,
                    Integer count, ZonedDateTime until) {

  public enum Freq { MINUTELY, HOURLY, DAILY, WEEKLY, MONTHLY, YEARLY }

  /** Days scanned before giving up; comfortably covers a yearly rule. */
  private static final int MAX_DAYS_SCANNED = 800;

  public static boolean looksLikeRrule(String expression) {
    return expression != null && expression.toUpperCase(Locale.ROOT).contains("FREQ=");
  }

  public static Rrule parse(String expression) {
    if (expression == null || expression.isBlank()) {
      throw new ValidationException("An empty recurrence rule");
    }
    String body = expression.trim();
    if (body.toUpperCase(Locale.ROOT).startsWith("RRULE:")) body = body.substring(6);

    Freq freq = null;
    int interval = 1;
    Set<DayOfWeek> byDay = new LinkedHashSet<>();
    Set<Integer> byMonthDay = new TreeSet<>();
    Set<Integer> byMonth = new TreeSet<>();
    Set<Integer> byHour = new TreeSet<>();
    Set<Integer> byMinute = new TreeSet<>();
    Integer count = null;
    ZonedDateTime until = null;

    for (String part : body.split(";")) {
      if (part.isBlank()) continue;
      int eq = part.indexOf('=');
      if (eq < 0) throw new ValidationException("Malformed recurrence part '" + part + "'");
      String key = part.substring(0, eq).trim().toUpperCase(Locale.ROOT);
      String value = part.substring(eq + 1).trim();

      switch (key) {
        case "FREQ" -> freq = parseFreq(value);
        case "INTERVAL" -> interval = Math.max(1, Integer.parseInt(value));
        case "BYDAY" -> {
          for (String day : value.split(",")) byDay.add(parseDay(day));
        }
        case "BYMONTHDAY" -> {
          for (String day : value.split(",")) byMonthDay.add(Integer.parseInt(day.trim()));
        }
        case "BYMONTH" -> {
          for (String month : value.split(",")) byMonth.add(Integer.parseInt(month.trim()));
        }
        case "BYHOUR" -> {
          for (String hour : value.split(",")) byHour.add(Integer.parseInt(hour.trim()));
        }
        case "BYMINUTE" -> {
          for (String minute : value.split(",")) byMinute.add(Integer.parseInt(minute.trim()));
        }
        case "COUNT" -> count = Integer.parseInt(value);
        case "UNTIL" -> until = parseUntil(value);
        case "WKST" -> { /* Week start only matters for BYWEEKNO, which is out of scope. */ }
        default -> throw new ValidationException("Unsupported recurrence part '" + key
            + "'. Supported: FREQ, INTERVAL, BYDAY, BYMONTHDAY, BYMONTH, BYHOUR, BYMINUTE, "
            + "COUNT, UNTIL.");
      }
    }
    if (freq == null) throw new ValidationException("A recurrence rule needs a FREQ");
    return new Rrule(freq, interval, byDay, byMonthDay, byMonth, byHour, byMinute, count, until);
  }

  /**
   * @param anchor  the rule's origin, which fixes both the INTERVAL phase and the time of day when
   *                no BYHOUR/BYMINUTE is given
   * @param after   occurrences must be strictly after this
   * @param already how many occurrences have already fired, for COUNT
   */
  public Optional<ZonedDateTime> next(ZonedDateTime anchor, ZonedDateTime after, int already) {
    if (count != null && already >= count) return Optional.empty();

    ZonedDateTime cursor = after.isBefore(anchor) ? anchor.minusNanos(1) : after;

    if (freq == Freq.MINUTELY || freq == Freq.HOURLY) {
      long unit = freq == Freq.MINUTELY ? 60 : 3600;
      long elapsed = Math.max(0, ChronoUnit.SECONDS.between(anchor, cursor));
      long steps = elapsed / (unit * interval) + 1;
      ZonedDateTime candidate = anchor.plusSeconds(steps * unit * interval);
      return withinUntil(candidate);
    }

    List<LocalTime> times = timesOfDay(anchor);
    LocalDate day = cursor.toLocalDate();

    for (int scanned = 0; scanned <= MAX_DAYS_SCANNED; scanned++, day = day.plusDays(1)) {
      if (!dayMatches(anchor.toLocalDate(), day)) continue;
      for (LocalTime time : times) {
        // Building the local date-time in the zone is what makes this DST-safe: the zone rules
        // decide the instant, including on the two days a year where a wall-clock time is
        // ambiguous or does not exist.
        ZonedDateTime candidate = day.atTime(time).atZone(anchor.getZone());
        if (candidate.isAfter(cursor)) return withinUntil(candidate);
      }
    }
    return Optional.empty();
  }

  private Optional<ZonedDateTime> withinUntil(ZonedDateTime candidate) {
    if (until != null && candidate.isAfter(until)) return Optional.empty();
    return Optional.of(candidate);
  }

  private List<LocalTime> timesOfDay(ZonedDateTime anchor) {
    if (byHour.isEmpty() && byMinute.isEmpty()) {
      return List.of(anchor.toLocalTime().withSecond(0).withNano(0));
    }
    Set<Integer> hours = byHour.isEmpty() ? Set.of(anchor.getHour()) : byHour;
    Set<Integer> minutes = byMinute.isEmpty() ? Set.of(0) : byMinute;
    List<LocalTime> times = new ArrayList<>();
    for (Integer hour : hours) {
      for (Integer minute : minutes) times.add(LocalTime.of(hour, minute));
    }
    times.sort(java.util.Comparator.naturalOrder());
    return times;
  }

  private boolean dayMatches(LocalDate anchorDate, LocalDate day) {
    if (day.isBefore(anchorDate)) return false;
    if (!byMonth.isEmpty() && !byMonth.contains(day.getMonthValue())) return false;
    if (!byMonthDay.isEmpty() && !byMonthDay.contains(day.getDayOfMonth())) return false;
    if (!byDay.isEmpty() && !byDay.contains(day.getDayOfWeek())) return false;

    return switch (freq) {
      case DAILY -> ChronoUnit.DAYS.between(anchorDate, day) % interval == 0;
      case WEEKLY -> {
        LocalDate anchorWeek = anchorDate.with(DayOfWeek.MONDAY);
        LocalDate dayWeek = day.with(DayOfWeek.MONDAY);
        yield ChronoUnit.WEEKS.between(anchorWeek, dayWeek) % interval == 0;
      }
      case MONTHLY -> {
        if (ChronoUnit.MONTHS.between(anchorDate.withDayOfMonth(1), day.withDayOfMonth(1))
            % interval != 0) {
          yield false;
        }
        // Without BYDAY or BYMONTHDAY, a monthly rule repeats the anchor's day of month.
        yield !byDay.isEmpty() || !byMonthDay.isEmpty()
            || day.getDayOfMonth() == anchorDate.getDayOfMonth();
      }
      case YEARLY -> {
        if (ChronoUnit.YEARS.between(anchorDate.withDayOfYear(1), day.withDayOfYear(1))
            % interval != 0) {
          yield false;
        }
        yield (!byMonth.isEmpty() || day.getMonthValue() == anchorDate.getMonthValue())
            && (!byMonthDay.isEmpty() || !byDay.isEmpty()
                || day.getDayOfMonth() == anchorDate.getDayOfMonth());
      }
      default -> true;
    };
  }

  private static Freq parseFreq(String value) {
    try {
      return Freq.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new ValidationException("Unsupported FREQ '" + value + "'");
    }
  }

  private static DayOfWeek parseDay(String value) {
    return switch (value.trim().toUpperCase(Locale.ROOT)) {
      case "MO" -> DayOfWeek.MONDAY;
      case "TU" -> DayOfWeek.TUESDAY;
      case "WE" -> DayOfWeek.WEDNESDAY;
      case "TH" -> DayOfWeek.THURSDAY;
      case "FR" -> DayOfWeek.FRIDAY;
      case "SA" -> DayOfWeek.SATURDAY;
      case "SU" -> DayOfWeek.SUNDAY;
      default -> throw new ValidationException("Unsupported BYDAY value '" + value + "'");
    };
  }

  private static ZonedDateTime parseUntil(String value) {
    try {
      String v = value.trim();
      if (v.endsWith("Z")) {
        return ZonedDateTime.parse(v, DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(java.time.ZoneOffset.UTC));
      }
      return LocalDate.parse(v, DateTimeFormatter.BASIC_ISO_DATE)
          .atTime(23, 59, 59).atZone(java.time.ZoneOffset.UTC);
    } catch (RuntimeException e) {
      throw new ValidationException("UNTIL must be YYYYMMDD or YYYYMMDDTHHMMSSZ, got '" + value + "'");
    }
  }
}
