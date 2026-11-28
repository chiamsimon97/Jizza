package com.jizza.platform.usecase.vita.insights;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.vita.VitaDoseStore;
import com.jizza.engine.api.vita.VitaRecords;
import com.jizza.platform.usecase.vita.config.VitaConfigService;
import com.jizza.platform.usecase.vita.journal.VitaJournalUseCase;
import com.jizza.platform.usecase.vita.measure.VitaMeasurementUseCase;
import com.jizza.platform.usecase.vita.medicine.VitaMedicineUseCase;
import com.jizza.platform.usecase.vita.ranges.TargetEvaluator;
import com.jizza.platform.usecase.vita.ranges.VitaTargetService;
import com.jizza.platform.usecase.vita.records.VitaRecordsUseCase;
import com.jizza.platform.usecase.vita.sharing.VitaScopeResolver;
import com.jizza.platform.usecase.vita.sharing.VitaScopeResolver.PersonScope;
import com.jizza.platform.usecase.vita.sharing.VitaSection;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * "Goes with" (spec 5.9, Phase 4): for a person with at least {@code insights.min_journal_days}
 * journal days, compares mood, energy and pain with sleep, steps, missed doses and readings
 * outside range, day by day. A pair is reported only when it has that many days with both values
 * and its rank correlation (Spearman's rho) is at least {@code insights.min_strength} either way;
 * both are product choices in config. It says "goes with", never "causes", and every statement
 * shows the days behind it with the ids of their rows. Below the journal days it says nothing.
 *
 * <p>Readable by whoever may read the person's journal: the manager and a share of the journal.
 */
@Service
public class VitaCorrelationService {

  public static final String DISCLAIMER = VitaWeeklyNoteService.DISCLAIMER;
  /** How far back Vita looks: the last year of journal days (a product choice). */
  static final int WINDOW_DAYS = 365;
  static final String NOT_CAUSE = "Going together does not mean one causes the other.";

  private final VitaScopeResolver scopes;
  private final VitaRecords records;
  private final VitaDoseStore doses;
  private final VitaMeasurementUseCase measurements;
  private final VitaMedicineUseCase medicines;
  private final VitaTargetService targets;
  private final VitaConfigService config;

  public VitaCorrelationService(VitaScopeResolver scopes, VitaRecords records, VitaDoseStore doses,
                                VitaMeasurementUseCase measurements, VitaMedicineUseCase medicines,
                                VitaTargetService targets, VitaConfigService config) {
    this.scopes = scopes;
    this.records = records;
    this.doses = doses;
    this.measurements = measurements;
    this.medicines = medicines;
    this.targets = targets;
    this.config = config;
  }

  /** One day's value with the rows it came from. */
  record Point(BigDecimal value, List<String> rows) {}

  static final Map<String, String> JOURNAL = Map.of("mood", "Mood", "energy", "Energy", "pain", "Pain");
  static final List<String> JOURNAL_ORDER = List.of("mood", "energy", "pain");
  static final List<String> FACTORS = List.of("sleep", "steps", "missed_doses", "outside_range");

  public ObjectNode insights(UUID caller, UUID personId) {
    PersonScope p = scopes.person(caller, personId, VitaSection.JOURNAL, false);
    return insights(p, Instant.now());
  }

  public ObjectNode insights(PersonScope p, Instant now) {
    ZoneId zone = medicines.settings(p).zone();
    int minDays = setting(p.owner(), "insights.min_journal_days", "days", 21);
    double minStrength = setting(p.owner(), "insights.min_strength", "rho", 0.4);
    LocalDate today = now.atZone(zone).toLocalDate();
    LocalDate from = today.minusDays(WINDOW_DAYS - 1);

    Map<String, Map<LocalDate, Point>> journal = new LinkedHashMap<>();
    JOURNAL_ORDER.forEach(f -> journal.put(f, new TreeMap<>()));
    int journalDays = 0;
    for (ObjectNode e : records.list(VitaJournalUseCase.TABLE, p.owner(), VitaRecordsUseCase.livePerson(p.personId()))) {
      LocalDate d = LocalDate.parse(e.path("entryDate").asText());
      if (d.isBefore(from) || d.isAfter(today)) continue;
      boolean any = false;
      for (String f : JOURNAL_ORDER) {
        if (e.hasNonNull(f)) {
          journal.get(f).put(d, new Point(e.get(f).decimalValue(), List.of(e.path("id").asText())));
          any = true;
        }
      }
      if (any) journalDays++;
    }

    ObjectNode out = Json.obj().put("personId", p.personId().toString()).put("disclaimer", DISCLAIMER)
        .put("minJournalDays", minDays).put("minStrength", minStrength).put("journalDays", journalDays)
        .put("from", from.toString()).put("to", today.toString());
    ArrayNode statements = out.putArray("statements");
    if (journalDays < minDays) {
      out.put("silent", true).put("message", "Vita looks for what goes together once the journal has " + minDays
          + " days; it has " + journalDays + ".");
      return out;
    }

    Map<String, Map<LocalDate, Point>> factors = factors(p, from, today, zone);
    for (String j : JOURNAL_ORDER) {
      for (String f : FACTORS) {
        Map<LocalDate, Point> a = journal.get(j);
        Map<LocalDate, Point> b = factors.get(f);
        List<LocalDate> days = a.keySet().stream().filter(b::containsKey).sorted().toList();
        if (days.size() < minDays) continue;
        double[] x = days.stream().mapToDouble(d -> a.get(d).value().doubleValue()).toArray();
        double[] y = days.stream().mapToDouble(d -> b.get(d).value().doubleValue()).toArray();
        Double rho = spearman(x, y);
        if (rho == null || Math.abs(rho) < minStrength) continue;
        statements.add(statement(j, f, rho, days, a, b));
      }
    }
    out.put("silent", statements.isEmpty());
    if (statements.isEmpty()) out.put("message", "Nothing in the journal goes clearly with sleep, steps, missed doses or readings yet.");
    else out.put("note", NOT_CAUSE);
    return out;
  }

  private ObjectNode statement(String j, String f, double rho, List<LocalDate> days, Map<LocalDate, Point> a,
                               Map<LocalDate, Point> b) {
    String direction = rho > 0 ? "higher" : "lower";
    String text = JOURNAL.get(j) + " goes with " + factorText(f) + ": on days with more " + factorText(f)
        + ", " + j + " tended to be " + direction + ".";
    ObjectNode s = Json.obj().put("journalField", j).put("factor", f).put("text", text)
        .put("rho", BigDecimal.valueOf(rho).setScale(2, RoundingMode.HALF_UP)).put("days", days.size());
    // The two halves of the days, split at the factor's median, with the journal average of each.
    double[] fv = days.stream().mapToDouble(d -> b.get(d).value().doubleValue()).sorted().toArray();
    double median = fv.length % 2 == 1 ? fv[fv.length / 2] : (fv[fv.length / 2 - 1] + fv[fv.length / 2]) / 2;
    List<BigDecimal> above = new ArrayList<>();
    List<BigDecimal> rest = new ArrayList<>();
    for (LocalDate d : days) (b.get(d).value().doubleValue() > median ? above : rest).add(a.get(d).value());
    ObjectNode split = s.putObject("split").put("factorMedian", BigDecimal.valueOf(median).setScale(2, RoundingMode.HALF_UP))
        .put("daysAbove", above.size()).put("daysAtOrBelow", rest.size());
    if (!above.isEmpty()) split.put("journalAverageAbove", avg(above));
    if (!rest.isEmpty()) split.put("journalAverageAtOrBelow", avg(rest));
    ArrayNode data = s.putArray("data");
    for (LocalDate d : days) {
      ObjectNode row = data.addObject().put("date", d.toString()).put(j, a.get(d).value()).put(f, b.get(d).value());
      ArrayNode ids = row.putArray("rowIds");
      a.get(d).rows().forEach(ids::add);
      b.get(d).rows().forEach(ids::add);
    }
    return s;
  }

  /** The day-by-day factors: sleep hours (by the day it ended), steps, missed doses and readings outside range. */
  private Map<String, Map<LocalDate, Point>> factors(PersonScope p, LocalDate from, LocalDate to, ZoneId zone) {
    Map<String, Map<LocalDate, Point>> out = new LinkedHashMap<>();
    FACTORS.forEach(f -> out.put(f, new TreeMap<>()));
    VitaTargetService.Context ctx = targets.context(p);
    Map<LocalDate, List<String>> readingDays = new TreeMap<>();
    Map<LocalDate, List<String>> outsideDays = new TreeMap<>();
    for (ObjectNode m : measurements.list(p, null)) {
      String kind = m.path("kind").asText();
      String id = m.path("id").asText();
      switch (kind) {
        case "sleep" -> {
          Instant a = VitaWeeklyNoteService.instant(m.path("startedAt").asText(null));
          Instant b = VitaWeeklyNoteService.instant(m.path("endedAt").asText(null));
          if (a == null || b == null || !b.isAfter(a)) continue;
          LocalDate d = b.atZone(zone).toLocalDate();
          if (d.isBefore(from) || d.isAfter(to)) continue;
          BigDecimal hours = BigDecimal.valueOf(Duration.between(a, b).toMinutes()).divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);
          out.get("sleep").merge(d, new Point(hours, List.of(id)), VitaCorrelationService::plus);
        }
        case "steps" -> {
          String day = m.path("detail").path("date").asText(null);
          Instant at = VitaWeeklyNoteService.instant(m.path("takenAt").asText(null));
          LocalDate d = day != null ? LocalDate.parse(day) : at == null ? null : at.atZone(zone).toLocalDate();
          if (d == null || d.isBefore(from) || d.isAfter(to) || !m.hasNonNull("value")) continue;
          out.get("steps").merge(d, new Point(m.get("value").decimalValue(), List.of(id)), VitaCorrelationService::plus);
        }
        default -> {
          if (VitaMeasurementUseCase.readingOf(m) == null) continue;
          Instant at = VitaWeeklyNoteService.instant(m.path("takenAt").asText(null));
          if (at == null) continue;
          LocalDate d = at.atZone(zone).toLocalDate();
          if (d.isBefore(from) || d.isAfter(to)) continue;
          readingDays.computeIfAbsent(d, k -> new ArrayList<>()).add(id);
          String status = VitaMeasurementUseCase.withEvaluation(m, ctx).path("evaluation").path("status").asText("");
          if (TargetEvaluator.OUTSIDE_RANGE.equals(status) || TargetEvaluator.RED_FLAG.equals(status)) {
            outsideDays.computeIfAbsent(d, k -> new ArrayList<>()).add(id);
          }
        }
      }
    }
    // Only days with a reading count for readings outside range, so a day without one is not a zero.
    readingDays.forEach((d, ids) -> {
      List<String> outside = outsideDays.getOrDefault(d, List.of());
      out.get("outside_range").put(d, new Point(BigDecimal.valueOf(outside.size()), outside.isEmpty() ? ids : outside));
    });
    // Only days with doses due count for missed doses.
    Instant a = from.atStartOfDay(zone).toInstant();
    Instant b = to.plusDays(1).atStartOfDay(zone).toInstant();
    Map<LocalDate, List<String>> due = new TreeMap<>();
    Map<LocalDate, List<String>> missed = new TreeMap<>();
    for (VitaDoseStore.DoseRow d : doses.forPerson(p.owner(), p.personId(), a, b)) {
      if (d.dueAt().isAfter(Instant.now())) continue;
      LocalDate day = d.dueAt().atZone(zone).toLocalDate();
      due.computeIfAbsent(day, k -> new ArrayList<>()).add(d.id().toString());
      if ("missed".equals(d.status())) missed.computeIfAbsent(day, k -> new ArrayList<>()).add(d.id().toString());
    }
    due.forEach((d, ids) -> {
      List<String> m = missed.getOrDefault(d, List.of());
      out.get("missed_doses").put(d, new Point(BigDecimal.valueOf(m.size()), m.isEmpty() ? ids : m));
    });
    return out;
  }

  private static Point plus(Point x, Point y) {
    List<String> rows = new ArrayList<>(x.rows());
    rows.addAll(y.rows());
    return new Point(x.value().add(y.value()), rows);
  }

  static String factorText(String f) {
    return switch (f) {
      case "sleep" -> "sleep";
      case "steps" -> "steps";
      case "missed_doses" -> "missed doses";
      default -> "readings outside the usual range";
    };
  }

  /** Spearman's rank correlation, ties given their average rank; null when either side never varies. */
  static Double spearman(double[] x, double[] y) {
    double[] rx = ranks(x);
    double[] ry = ranks(y);
    double mx = Arrays.stream(rx).average().orElse(0);
    double my = Arrays.stream(ry).average().orElse(0);
    double sxy = 0;
    double sxx = 0;
    double syy = 0;
    for (int i = 0; i < rx.length; i++) {
      sxy += (rx[i] - mx) * (ry[i] - my);
      sxx += (rx[i] - mx) * (rx[i] - mx);
      syy += (ry[i] - my) * (ry[i] - my);
    }
    if (sxx == 0 || syy == 0) return null;
    return sxy / Math.sqrt(sxx * syy);
  }

  static double[] ranks(double[] v) {
    Integer[] idx = new Integer[v.length];
    for (int i = 0; i < v.length; i++) idx[i] = i;
    Arrays.sort(idx, (i, j) -> Double.compare(v[i], v[j]));
    double[] r = new double[v.length];
    int i = 0;
    while (i < idx.length) {
      int j = i;
      while (j + 1 < idx.length && v[idx[j + 1]] == v[idx[i]]) j++;
      double rank = (i + j) / 2.0 + 1;
      for (int k = i; k <= j; k++) r[idx[k]] = rank;
      i = j + 1;
    }
    return r;
  }

  private static BigDecimal avg(List<BigDecimal> v) {
    BigDecimal sum = v.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    return sum.divide(BigDecimal.valueOf(v.size()), 1, RoundingMode.HALF_UP);
  }

  private int setting(UUID owner, String key, String field, int fallback) {
    try {
      var item = config.item(owner, key);
      return item.value() == null ? fallback : item.value().path(field).asInt(fallback);
    } catch (RuntimeException e) {
      return fallback;
    }
  }

  private double setting(UUID owner, String key, String field, double fallback) {
    try {
      var item = config.item(owner, key);
      return item.value() == null ? fallback : item.value().path(field).asDouble(fallback);
    } catch (RuntimeException e) {
      return fallback;
    }
  }
}
