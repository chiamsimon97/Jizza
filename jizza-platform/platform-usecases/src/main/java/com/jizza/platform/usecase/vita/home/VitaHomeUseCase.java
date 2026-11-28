package com.jizza.platform.usecase.vita.home;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.vita.VitaRecords;
import com.jizza.platform.usecase.vita.config.VitaConfigService;
import com.jizza.platform.usecase.vita.measure.VitaMeasurementUseCase;
import com.jizza.platform.usecase.vita.quickstart.VitaQuickStartUseCase;
import com.jizza.platform.usecase.vita.ranges.RangeSet;
import com.jizza.platform.usecase.vita.ranges.TargetEvaluator;
import com.jizza.platform.usecase.vita.ranges.TargetEvaluator.Evaluation;
import com.jizza.platform.usecase.vita.ranges.TrendService;
import com.jizza.platform.usecase.vita.ranges.TrendService.Point;
import com.jizza.platform.usecase.vita.ranges.TrendService.Trend;
import com.jizza.platform.usecase.vita.ranges.VitaTargetService;
import com.jizza.platform.usecase.vita.records.VitaRecordsUseCase;
import com.jizza.platform.usecase.vita.records.VitaTable;
import com.jizza.platform.usecase.vita.sharing.VitaScopeResolver;
import com.jizza.platform.usecase.vita.sharing.VitaScopeResolver.PersonScope;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * The read-side views of Phase 1a: trends (5.5), condition summaries (5.6), the doctors list with
 * next visits and the targets each doctor set (7.10), and Home's cards in one call. Every view
 * quotes the evaluator and the trend functions; nothing here works out a figure of its own.
 */
@Service
public class VitaHomeUseCase {

  /** Product choice when config holds none: how long a red-flag banner stays on Home. */
  static final int DEFAULT_RED_FLAG_HOURS = 24;
  static final List<String> HOME_KINDS = List.of("bp", "glucose", "weight", "heart_rate", "spo2", "temperature");

  private final VitaRecords records;
  private final VitaScopeResolver scopes;
  private final VitaConfigService config;
  private final VitaTargetService targets;
  private final VitaMeasurementUseCase readings;
  private final VitaRecordsUseCase forms;
  private final VitaQuickStartUseCase quickStart;
  private final ObjectProvider<HomeExtras> extras;

  public VitaHomeUseCase(VitaRecords records, VitaScopeResolver scopes, VitaConfigService config,
                         VitaTargetService targets, VitaMeasurementUseCase readings, VitaRecordsUseCase forms,
                         VitaQuickStartUseCase quickStart, ObjectProvider<HomeExtras> extras) {
    this.records = records;
    this.scopes = scopes;
    this.config = config;
    this.targets = targets;
    this.readings = readings;
    this.forms = forms;
    this.quickStart = quickStart;
    this.extras = extras;
  }

  /**
   * Cards that later parts add to Home and to condition summaries (medicines and time slots in
   * Phase 1a part 2, lab results and checks in Phase 1b), without this class knowing them.
   */
  public interface HomeExtras {
    void addToHome(PersonScope p, ObjectNode home);

    void addToSummary(PersonScope p, ObjectNode condition, ObjectNode summary);
  }

  // ── Trends ──────────────────────────────────────────────────────────────────

  /** The trend of one kind over the last {@code days} days (1 to 365). */
  public ObjectNode trend(UUID caller, UUID personId, String kind, Integer days, String context) {
    PersonScope p = scopes.person(caller, personId, com.jizza.platform.usecase.vita.sharing.VitaSection.VITALS, false);
    return trend(p, targets.context(p), kind, days == null ? 30 : days, context);
  }

  ObjectNode trend(PersonScope p, VitaTargetService.Context ctx, String kind, int days, String context) {
    if (!VitaMeasurementUseCase.ALL_KINDS.contains(kind)) throw new ValidationException("kind is not a reading kind.");
    if (days < 1 || days > 365) throw new ValidationException("days must be from 1 to 365.");
    if (context != null && !VitaMeasurementUseCase.CONTEXTS.contains(context)) throw new ValidationException("context is not known.");
    ZoneId zone = zone(p);
    Instant from = Instant.now().minus(Duration.ofDays(days));
    List<ObjectNode> rows = readings.list(p, kind).stream()
        .filter(r -> r.hasNonNull("takenAt") && !Instant.parse(r.get("takenAt").asText()).isBefore(from))
        .filter(r -> context == null || context.equals(r.path("context").asText(null))
            || ("before_meal".equals(context) && "fasting".equals(r.path("context").asText(null))))
        .toList();
    Trend t = trendOf(kind, rows, ctx, zone);
    ObjectNode out = Json.obj().put("kind", kind).put("days", days).put("zone", zone.getId());
    if (context != null) out.put("context", context);
    RangeSet range = ctx.rangeFor(kind, context, "bp".equals(kind) ? "home" : null);
    out.set("range", range == null ? Json.obj().put("status", TargetEvaluator.NOT_SET).put("label", TargetEvaluator.NOT_SET_LABEL)
        : Json.obj().put("status", "set").put("range", range.text()).put("source", range.source())
            .put("label", range.label()).put("needsVerification", range.needsVerification()));
    out.set("trend", Json.toNode(t));
    return out;
  }

  /**
   * Builds the trend from stored rows: pairs are joined first, then each point is evaluated, so a
   * pair's status is the status of its average.
   */
  public static Trend trendOf(String kind, List<ObjectNode> rows, VitaTargetService.Context ctx, ZoneId zone) {
    List<Point> raw = rows.stream().map(r -> new Point(UUID.fromString(r.path("id").asText()),
        Instant.parse(r.get("takenAt").asText()), dec(r, "value"), dec(r, "value2"),
        r.hasNonNull("pairId") ? UUID.fromString(r.get("pairId").asText()) : null, null)).toList();
    Map<UUID, ObjectNode> byId = new LinkedHashMap<>();
    rows.forEach(r -> byId.put(UUID.fromString(r.path("id").asText()), r));
    List<Point> evaluated = new ArrayList<>();
    for (Point pt : TrendService.collapsePairs(raw)) {
      ObjectNode r = byId.get(pt.id());
      TargetEvaluator.Reading reading = new TargetEvaluator.Reading(kind, pt.value(), pt.value2(),
          r == null ? null : r.path("context").asText(null), r == null ? null : r.path("setting").asText(null));
      String status = VitaMeasurementUseCase.readingOf(r) == null ? null : ctx.evaluate(reading, null).status();
      evaluated.add(new Point(pt.id(), pt.takenAt(), pt.value(), pt.value2(), null, status));
    }
    return TrendService.trend(kind, evaluated, zone);
  }

  /** The ranges screen (delegates to the target service). */
  public List<VitaTargetService.RangeView> ranges(UUID caller, UUID personId) {
    return targets.ranges(caller, personId);
  }

  // ── Condition summary ───────────────────────────────────────────────────────

  /** The condition page's data (spec 5.6). */
  public ObjectNode conditionSummary(UUID caller, UUID personId, UUID conditionId) {
    PersonScope p = scopes.person(caller, personId, com.jizza.platform.usecase.vita.sharing.VitaSection.CONDITIONS, false);
    ObjectNode c = forms.get(caller, personId, VitaTable.CONDITION, conditionId);
    var ctx = targets.context(p);
    String template = c.path("template").asText("generic");
    ObjectNode out = Json.obj();
    out.set("condition", c);
    out.put("template", template);
    if (c.hasNonNull("doctorId")) {
      records.get("vita_doctor", p.owner(), UUID.fromString(c.get("doctorId").asText()))
          .filter(d -> d.path("deletedAt").isNull()).ifPresent(d -> out.set("doctor", d));
    }
    ArrayNode measures = out.putArray("measures");
    switch (template) {
      case "diabetes_t2" -> {
        for (String context : List.of("before_meal", "after_meal")) {
          measures.add(Json.obj().put("kind", "glucose").put("context", context)
              .<ObjectNode>set("last30", trend(p, ctx, "glucose", 30, context))
              .set("last90", trend(p, ctx, "glucose", 90, context)));
        }
        ArrayNode low = out.putArray("lowReadings");
        readings.list(p, "glucose").stream().map(r -> VitaMeasurementUseCase.withEvaluation(r, ctx))
            .filter(r -> "low_sugar".equals(r.path("evaluation").path("redFlag").path("ruleId").asText()))
            .limit(20).forEach(low::add);
        out.put("labs", "Add a report to see HbA1c.");
        out.set("checks", intervals(p, "clinical.intervals.diabetes_t2.",
            List.of("hba1c", "eye", "foot", "urine_albumin", "kidney_function", "lipids")));
      }
      case "hypertension" -> {
        measures.add(Json.obj().put("kind", "bp").<ObjectNode>set("last7", trend(p, ctx, "bp", 7, null))
            .set("last30", trend(p, ctx, "bp", 30, null)));
        out.put("labs", "Add a report to see kidney and lipid tests.");
        out.set("checks", intervals(p, "clinical.intervals.hypertension.",
            List.of("bp_checks", "kidney_function", "lipids")));
      }
      default -> {
        for (JsonNode k : c.path("measures")) {
          String kind = k.asText();
          if (VitaMeasurementUseCase.ALL_KINDS.contains(kind)) {
            measures.add(Json.obj().put("kind", kind).set("last30", trend(p, ctx, kind, 30, null)));
          }
        }
        out.putArray("checks");
      }
    }
    if (!p.canView(com.jizza.platform.usecase.vita.sharing.VitaSection.VITALS)) {
      out.putArray("measures");
      out.remove("lowReadings");
    }
    out.putArray("medicines");
    extras.orderedStream().forEach(x -> x.addToSummary(p, c, out));
    return out;
  }

  /** Check intervals with their state: a figure with no source reads "not set". */
  private ArrayNode intervals(PersonScope p, String prefix, List<String> names) {
    ArrayNode out = Json.mapper().createArrayNode();
    var items = config.list(p.owner());
    for (String name : names) {
      Optional<VitaConfigService.ConfigItem> item = items.stream().filter(i -> i.key().equals(prefix + name)).findFirst();
      ObjectNode n = out.addObject().put("check", name);
      if (item.isEmpty() || item.get().value() == null) {
        n.put("state", VitaConfigService.NOT_SET_STATE).put("label", TargetEvaluator.NOT_SET_LABEL);
      } else {
        n.put("state", item.get().state()).put("label", item.get().label()).set("interval", item.get().value());
      }
    }
    return out;
  }

  // ── Doctors ─────────────────────────────────────────────────────────────────

  /** Doctors with their next planned visit (any person of the household) and the targets they set. */
  public List<ObjectNode> doctors(UUID caller) {
    UUID owner = scopes.own(caller).ownerUserId();
    Map<String, String> names = new LinkedHashMap<>();
    profilesOf(caller).forEach((id, name) -> names.put(id.toString(), name));
    List<ObjectNode> visits = records.list("vita_visit", owner, Map.of()).stream()
        .filter(v -> v.path("deletedAt").isNull() && "planned".equals(v.path("status").asText())
            && v.hasNonNull("scheduledAt") && Instant.parse(v.get("scheduledAt").asText()).isAfter(Instant.now()))
        .sorted(Comparator.comparing(v -> v.get("scheduledAt").asText())).toList();
    List<ObjectNode> out = new ArrayList<>();
    for (ObjectNode d : forms.listHousehold(caller, VitaTable.DOCTOR)) {
      ObjectNode n = d.deepCopy();
      String id = d.path("id").asText();
      visits.stream().filter(v -> id.equals(v.path("doctorId").asText(null))).findFirst().ifPresent(v -> n.putObject("nextVisit")
          .put("visitId", v.path("id").asText()).put("scheduledAt", v.get("scheduledAt").asText())
          .put("personId", v.path("personId").asText()).put("personName", names.get(v.path("personId").asText())));
      ArrayNode t = n.putArray("targets");
      targets.targetsBy(owner, UUID.fromString(id)).forEach(r -> t.add(r.deepCopy()
          .put("personName", names.get(r.path("personId").asText()))));
      out.add(n);
    }
    out.sort(Comparator.comparing((ObjectNode d) -> d.path("name").asText().toLowerCase()));
    return out;
  }

  private Map<UUID, String> profilesOf(UUID caller) {
    Map<UUID, String> out = new LinkedHashMap<>();
    UUID owner = scopes.own(caller).ownerUserId();
    for (ObjectNode v : records.list("vita_visit", owner, Map.of())) {
      UUID pid = UUID.fromString(v.path("personId").asText());
      out.computeIfAbsent(pid, k -> {
        try {
          return scopes.person(caller, k).person().displayName();
        } catch (NotFoundException e) {
          return null;
        }
      });
    }
    for (ObjectNode r : records.list("vita_usual_range", owner, Map.of())) {
      UUID pid = UUID.fromString(r.path("personId").asText());
      out.computeIfAbsent(pid, k -> {
        try {
          return scopes.person(caller, k).person().displayName();
        } catch (NotFoundException e) {
          return null;
        }
      });
    }
    return out;
  }

  // ── Home ────────────────────────────────────────────────────────────────────

  /** Home's cards for one person in one call. */
  public ObjectNode home(UUID caller, UUID personId) {
    PersonScope p = homeScope(caller, personId);
    var ctx = targets.context(p);
    ObjectNode out = Json.obj();
    ObjectNode person = out.putObject("person").put("id", p.personId().toString()).put("name", p.person().displayName())
        .put("recordOpen", p.open()).put("status", p.person().status()).put("shared", p.shared());
    if (p.shared()) {
      // A shared record (spec 5.10): what the caller holds, and only those sections' cards.
      person.put("asSelf", p.grant().asSelf());
      ObjectNode sections = person.putObject("sections");
      p.grant().sections().forEach((k, v) -> sections.put(k.key(), v));
      out.putNull("quickStart");
    } else {
      out.set("quickStart", Json.toNode(quickStart.get(caller, personId).progress()));
    }
    ArrayNode latest = out.putArray("latest");
    ArrayNode banners = out.putArray("redFlags");
    int hours = redFlagHours(p);
    Instant since = Instant.now().minus(Duration.ofHours(hours));
    for (String kind : p.canView(com.jizza.platform.usecase.vita.sharing.VitaSection.VITALS) ? HOME_KINDS : List.<String>of()) {
      readings.latest(p, kind).ifPresent(r -> {
        ObjectNode e = VitaMeasurementUseCase.withEvaluation(r, ctx);
        latest.add(e);
        Evaluation ev = VitaMeasurementUseCase.evaluationOf(r, ctx, null);
        if (ev != null && ev.redFlagged() && Instant.parse(r.get("takenAt").asText()).isAfter(since)) {
          String level = r.path("detail").path("redFlag").path("level").asText(ev.redFlag().level());
          banners.add(Json.obj().put("measurementId", r.path("id").asText()).put("kind", kind)
              .put("level", level).put("rule", ev.redFlag().ruleId())
              .put("confirmed", r.path("detail").path("redFlag").has("confirmedAt"))
              .put("advice", ev.redFlag().advice()).put("question", ev.redFlag().question())
              .put("needsVerification", ev.redFlag().needsVerification())
              .put("wording", "emergency".equals(level)
                  ? "Call now. This is at a level where doctors advise emergency care."
                  : "Act today. This is at a level where doctors advise urgent care."));
        }
      });
    }
    ArrayNode conditions = out.putArray("conditions");
    (p.canView(com.jizza.platform.usecase.vita.sharing.VitaSection.CONDITIONS) ? forms.live(p, VitaTable.CONDITION) : List.<ObjectNode>of()).stream().filter(c -> "active".equals(c.path("status").asText()))
        .forEach(c -> conditions.addObject().put("id", c.path("id").asText()).put("name", c.path("name").asText())
            .put("template", c.path("template").asText()));
    (p.canView(com.jizza.platform.usecase.vita.sharing.VitaSection.VISITS) ? forms.live(p, VitaTable.VISIT) : List.<ObjectNode>of()).stream()
        .filter(v -> "planned".equals(v.path("status").asText()) && v.hasNonNull("scheduledAt")
            && Instant.parse(v.get("scheduledAt").asText()).isAfter(Instant.now()))
        .min(Comparator.comparing(v -> v.get("scheduledAt").asText()))
        .ifPresentOrElse(v -> out.set("nextVisit", v), () -> out.putNull("nextVisit"));
    out.putArray("slots");
    out.putArray("reminders");
    extras.orderedStream().forEach(x -> x.addToHome(p, out));
    return out;
  }

  /** The caller's own person, or a record shared with them in any section. */
  private PersonScope homeScope(UUID caller, UUID personId) {
    try {
      return scopes.person(caller, personId);
    } catch (NotFoundException notOwn) {
      return scopes.sharedScope(caller, personId).orElseThrow(() -> notOwn);
    }
  }

  private int redFlagHours(PersonScope p) {
    return config.list(p.owner()).stream().filter(i -> i.key().equals("entry.red_flag_recent_hours"))
        .findFirst().map(i -> i.value() == null ? DEFAULT_RED_FLAG_HOURS : i.value().path("hours").asInt(DEFAULT_RED_FLAG_HOURS))
        .orElse(DEFAULT_RED_FLAG_HOURS);
  }

  /** The person's zone from their profile, India when unset. */
  public ZoneId zone(PersonScope p) {
    return records.list("vita_profile", p.owner(), Map.of("personId", p.personId())).stream().findFirst()
        .map(r -> r.path("timezone").asText("Asia/Kolkata"))
        .map(z -> {
          try {
            return ZoneId.of(z);
          } catch (Exception e) {
            return VitaRecordsUseCase.ZONE;
          }
        }).orElse(VitaRecordsUseCase.ZONE);
  }

  private static BigDecimal dec(ObjectNode r, String f) {
    return r.hasNonNull(f) ? r.get(f).decimalValue() : null;
  }
}
