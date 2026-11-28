package com.jizza.platform.usecase.vita.goals;

import static com.jizza.platform.usecase.vita.records.VitaField.decimal;
import static com.jizza.platform.usecase.vita.records.VitaField.of;
import static com.jizza.platform.usecase.vita.records.VitaField.oneOf;
import static com.jizza.platform.usecase.vita.records.VitaField.text;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.vita.VitaRecords;
import com.jizza.platform.usecase.vita.config.VitaConfigService;
import com.jizza.platform.usecase.vita.ranges.RangeSet;
import com.jizza.platform.usecase.vita.ranges.VitaTargetService;
import com.jizza.platform.usecase.vita.records.VitaField;
import com.jizza.platform.usecase.vita.records.VitaField.Type;
import com.jizza.platform.usecase.vita.records.VitaRecordsUseCase;
import com.jizza.platform.usecase.vita.sharing.VitaScopeResolver;
import com.jizza.platform.usecase.vita.sharing.VitaScopeResolver.PersonScope;
import com.jizza.platform.usecase.vita.sharing.VitaSection;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Health goals (spec 5.7, 7.14). A goal for blood pressure, sugar or HbA1c keeps no figure of its
 * own: it reads the doctor's target, else the Vita default from config (shown "needs
 * verification, confirm with your doctor"); with neither set it cannot be created. Never the
 * person's own range and never a number Vita made up. The weekly plan is what the user typed:
 * Vita proposes no plan figure ({@code clinical.goal_plans} is not set). Weeks run Monday to
 * Sunday in India time; a week with no typed check-in is filled from measurements where it can.
 * A goal is met when the weekly value meets the target for the weeks set in
 * {@code goals.met_after_weeks} (4 by default, a product choice).
 */
@Service
public class VitaGoalUseCase {

  public static final String TABLE = "vita_goal";
  public static final String CHECKINS = "vita_goal_checkin";
  public static final Set<String> MEDICAL = Set.of("bp_range", "glucose_range", "hba1c");
  static final int MAX_GOALS = 50;
  static final int WEEKS_SHOWN = 12;
  static final String NO_TARGET = "No target is set for this yet. Ask the doctor for one and add it "
      + "under Ranges; Vita does not make one up.";
  static final String CONFIRM = "Vita default, needs verification. Confirm with your doctor.";
  static final String PLAN_NOTE = "Vita does not suggest plan figures. Type the steps you and your doctor agreed.";

  static final Map<String, VitaField> FIELDS = fields(
      oneOf("kind", "weight", "steps", "sleep_hours", "bp_range", "glucose_range", "hba1c", "water", "custom").need(),
      text("title", 120), oneOf("context", "before_meal", "after_meal"), decimal("target", 100_000),
      decimal("target2", 100_000), decimal("startValue", 100_000), text("unit", 20), of("byDate", Type.DATE), VitaField.lines("weeklyPlan", 20),
      oneOf("status", "active", "met", "paused", "dropped"));

  static final Map<String, VitaField> CHECKIN_FIELDS = fields(
      of("weekStart", Type.PAST_DATE), decimal("value", 100_000), decimal("value2", 100_000), text("note", 500));

  private final VitaScopeResolver scopes;
  private final VitaRecords records;
  private final VitaTargetService targets;
  private final VitaConfigService config;
  private final AuditLog audit;

  public VitaGoalUseCase(VitaScopeResolver scopes, VitaRecords records, VitaTargetService targets,
                         VitaConfigService config, AuditLog audit) {
    this.scopes = scopes;
    this.records = records;
    this.targets = targets;
    this.config = config;
    this.audit = audit;
  }

  // ── Goals ───────────────────────────────────────────────────────────────────

  public List<ObjectNode> list(UUID caller, UUID personId) {
    PersonScope p = scopes.person(caller, personId, VitaSection.GOALS, false);
    VitaTargetService.Context ctx = targets.context(p);
    int weeks = metAfterWeeks(p.owner());
    return live(p).stream().map(g -> view(p, ctx, g, weeks)).toList();
  }

  public ObjectNode get(UUID caller, UUID personId, UUID id) {
    PersonScope p = scopes.person(caller, personId, VitaSection.GOALS, false);
    return view(p, targets.context(p), row(p, id), metAfterWeeks(p.owner()));
  }

  /**
   * What a new goal would show before it is saved: the target it would read (or that none is
   * set), and the plan steps Vita offers, which are none.
   */
  public ObjectNode preview(UUID caller, JsonNode body) {
    UUID personId = body != null && body.hasNonNull("personId") ? uuid(body.get("personId")) : null;
    PersonScope p = scopes.personOrSelf(caller, personId, VitaSection.GOALS, false);
    String kind = body == null ? null : body.path("kind").asText(null);
    if (kind == null || !FIELDS.get("kind").values().contains(kind)) {
      throw new ValidationException("kind must be one of " + String.join(", ", FIELDS.get("kind").values()) + ".");
    }
    String context = body.path("context").asText(null);
    ObjectNode out = Json.obj().put("personId", p.personId().toString()).put("kind", kind);
    if (MEDICAL.contains(kind)) {
      if ("glucose_range".equals(kind) && context == null) {
        out.put("canCreate", false).put("message", "Pick before or after a meal.");
      } else {
        RangeSet t = target(targets.context(p), kind, context);
        out.set("target", targetView(t));
        out.put("canCreate", t != null);
        out.put("message", t == null ? NO_TARGET : t.needsVerification() || RangeSet.VITA_DEFAULT.equals(t.source()) ? CONFIRM : t.label());
      }
    } else {
      out.putNull("target");
      out.put("canCreate", true);
      out.put("message", "custom".equals(kind) ? "Name the goal and, if you like, a weekly number to aim for."
          : "Type the number you are aiming for.");
    }
    out.putArray("plan");
    out.put("planNote", PLAN_NOTE);
    out.put("metAfterWeeks", metAfterWeeks(p.owner()));
    return out;
  }

  @Transactional
  public ObjectNode create(UUID caller, UUID personId, JsonNode body) {
    PersonScope p = scopes.person(caller, personId, VitaSection.GOALS, true).requireOpen();
    if (live(p).size() >= MAX_GOALS) throw new ValidationException("A record can hold at most " + MAX_GOALS + " goals.");
    ObjectNode values = VitaRecordsUseCase.clean(FIELDS, body, true);
    VitaTargetService.Context ctx = targets.context(p);
    checkGoal(ctx, values, null);
    values.put("personId", p.personId().toString());
    values.put("source", "manual");
    values.put("createdByUserId", p.caller().toString());
    if (!values.hasNonNull("status")) values.put("status", "active");
    if (!values.hasNonNull("unit")) defaultUnit(values);
    if ("weight".equals(values.path("kind").asText()) && !values.hasNonNull("startValue")) {
      // The latest weight on record says whether the goal is to go down or up.
      measurements(p, "weight").stream()
          .filter(m -> m.hasNonNull("value") && m.hasNonNull("takenAt"))
          .max(java.util.Comparator.comparing((ObjectNode m) -> m.path("takenAt").asText()))
          .ifPresent(m -> values.put("startValue", m.get("value").decimalValue()));
    }
    ObjectNode row = records.insert(TABLE, p.owner(), values);
    audit.record(p.owner(), ActorType.USER, caller, "vita.goal.added", row.path("id").asText(), null);
    return view(p, ctx, row, metAfterWeeks(p.owner()));
  }

  @Transactional
  public ObjectNode update(UUID caller, UUID personId, UUID id, JsonNode body) {
    PersonScope p = scopes.person(caller, personId, VitaSection.GOALS, true).requireOpen();
    ObjectNode current = row(p, id);
    VitaRecordsUseCase.requireOwnEntry(p, current, "Goal", id);
    ObjectNode values = VitaRecordsUseCase.clean(FIELDS, body, false);
    if (values.has("kind") && !values.path("kind").asText().equals(current.path("kind").asText())) {
      throw new ValidationException("A goal keeps its kind. Add a new goal instead.");
    }
    VitaTargetService.Context ctx = targets.context(p);
    checkGoal(ctx, values, current);
    if (values.has("status") && !"met".equals(values.path("status").asText())) values.putNull("metOn");
    if ("met".equals(values.path("status").asText(null)) && !"met".equals(current.path("status").asText())) {
      values.put("metOn", today().toString());
    }
    ObjectNode row = values.isEmpty() ? current
        : records.update(TABLE, p.owner(), id, values).orElseThrow(() -> new NotFoundException("Goal", id));
    audit.record(p.owner(), ActorType.USER, caller, "vita.goal.updated", id.toString(), null);
    return view(p, ctx, row, metAfterWeeks(p.owner()));
  }

  @Transactional
  public void delete(UUID caller, UUID personId, UUID id) {
    PersonScope p = scopes.person(caller, personId, VitaSection.GOALS, true).requireOpen().requireManager();
    row(p, id);
    records.update(TABLE, p.owner(), id, Json.obj().put("deletedAt", Instant.now().toString()));
    audit.record(p.owner(), ActorType.USER, caller, "vita.goal.removed", id.toString(), null);
  }

  // ── Check-ins ───────────────────────────────────────────────────────────────

  /**
   * A week's value as typed (this week by default). It replaces the week's earlier typed value,
   * and a goal that has now met its target for enough weeks in a row is marked met.
   */
  @Transactional
  public ObjectNode checkIn(UUID caller, UUID personId, UUID goalId, JsonNode body) {
    PersonScope p = scopes.person(caller, personId, VitaSection.GOALS, true).requireOpen();
    ObjectNode goal = row(p, goalId);
    ObjectNode values = VitaRecordsUseCase.clean(CHECKIN_FIELDS, body, false);
    LocalDate week = values.hasNonNull("weekStart") ? LocalDate.parse(values.get("weekStart").asText()) : weekOf(today());
    if (week.getDayOfWeek() != DayOfWeek.MONDAY) throw new ValidationException("weekStart must be a Monday.");
    values.put("weekStart", week.toString());
    boolean pair = "bp_range".equals(goal.path("kind").asText());
    if (!values.hasNonNull("value") || (pair && !values.hasNonNull("value2"))) {
      throw new ValidationException(pair ? "Type both numbers of the reading." : "Type the week's value.");
    }
    if (!pair) values.remove("value2");
    Map<String, Object> where = new LinkedHashMap<>();
    where.put("goalId", goalId);
    where.put("weekStart", week);
    Optional<ObjectNode> existing = records.list(CHECKINS, p.owner(), where).stream().findFirst();
    if (existing.isPresent()) {
      VitaRecordsUseCase.requireOwnEntry(p, existing.get(), "Check-in", UUID.fromString(existing.get().path("id").asText()));
      records.update(CHECKINS, p.owner(), UUID.fromString(existing.get().path("id").asText()), values);
    } else {
      values.put("goalId", goalId.toString());
      values.put("personId", p.personId().toString());
      values.put("source", "manual");
      values.put("createdByUserId", p.caller().toString());
      records.insert(CHECKINS, p.owner(), values);
    }
    audit.record(p.owner(), ActorType.USER, caller, "vita.goal.checked_in", goalId.toString(), null);
    VitaTargetService.Context ctx = targets.context(p);
    markIfMet(p, ctx, row(p, goalId));
    return view(p, ctx, row(p, goalId), metAfterWeeks(p.owner()));
  }

  /** The nightly run: marks the person's active goals that have met their target. */
  public int markMet(PersonScope p) {
    VitaTargetService.Context ctx = targets.context(p);
    int n = 0;
    for (ObjectNode g : live(p)) {
      if (markIfMet(p, ctx, g)) n++;
    }
    return n;
  }

  private boolean markIfMet(PersonScope p, VitaTargetService.Context ctx, ObjectNode goal) {
    if (!"active".equals(goal.path("status").asText())) return false;
    int need = metAfterWeeks(p.owner());
    if (streak(goal, target(ctx, goal), weeks(p, goal)) < need) return false;
    records.update(TABLE, p.owner(), UUID.fromString(goal.path("id").asText()),
        Json.obj().put("status", "met").put("metOn", today().toString()));
    return true;
  }

  // ── Views ───────────────────────────────────────────────────────────────────

  private ObjectNode view(PersonScope p, VitaTargetService.Context ctx, ObjectNode g, int metAfter) {
    ObjectNode v = g.deepCopy();
    v.remove("userId");
    v.remove("deletedAt");
    v.remove("sourceRef");
    String kind = g.path("kind").asText();
    RangeSet t = target(ctx, g);
    v.put("medical", MEDICAL.contains(kind));
    if (MEDICAL.contains(kind)) {
      v.set("target", targetView(t));
      if (t == null) v.put("targetNote", NO_TARGET);
    } else if (g.hasNonNull("target")) {
      ObjectNode own = Json.obj().put("text", plain(g.get("target").decimalValue())
          + (g.hasNonNull("unit") ? " " + g.get("unit").asText() : "")).put("source", "you").put("label", "Your goal");
      v.set("target", own);
    } else {
      v.putNull("target");
    }
    List<Week> weeks = weeks(p, g);
    ArrayNode checkins = v.putArray("checkins");
    for (Week w : weeks) {
      ObjectNode c = checkins.addObject().put("weekStart", w.start().toString()).put("source", w.source());
      putNum(c, "value", w.value());
      putNum(c, "value2", w.value2());
      Boolean meets = meets(g, t, w, weeks);
      if (meets == null) c.putNull("meets"); else c.put("meets", meets);
      if (w.note() != null) c.put("note", w.note());
    }
    v.put("weeksMetInARow", streak(g, t, weeks));
    v.put("metAfterWeeks", metAfter);
    v.put("planNote", PLAN_NOTE);
    if (!v.path("weeklyPlan").isArray()) v.set("weeklyPlan", Json.mapper().createArrayNode());
    return v;
  }

  static ObjectNode targetView(RangeSet t) {
    if (t == null) return null;
    ObjectNode o = Json.obj().put("text", t.text()).put("source", t.source()).put("label", t.label())
        .put("needsVerification", t.needsVerification() || RangeSet.VITA_DEFAULT.equals(t.source()));
    if (t.doctorId() != null) o.put("doctorId", t.doctorId().toString());
    if (t.doctorName() != null) o.put("doctorName", t.doctorName());
    if (t.setOn() != null) o.put("setOn", t.setOn().toString());
    if (t.configKey() != null) o.put("configKey", t.configKey());
    if (RangeSet.VITA_DEFAULT.equals(t.source())) o.put("note", CONFIRM);
    return o;
  }

  // ── Targets and weeks ───────────────────────────────────────────────────────

  /** The target a medical goal reads now, or null (also for any other kind). */
  private static RangeSet target(VitaTargetService.Context ctx, JsonNode goal) {
    return target(ctx, goal.path("kind").asText(), goal.path("context").asText(null));
  }

  static RangeSet target(VitaTargetService.Context ctx, String kind, String context) {
    return switch (kind) {
      case "bp_range" -> ctx.goalTargetFor("bp", null, "home");
      case "glucose_range" -> context == null ? null : ctx.goalTargetFor("glucose", context, null);
      case "hba1c" -> ctx.goalLabTargetFor("hba1c");
      default -> null;
    };
  }

  /** A week's value: the typed one, else what the measurements give. */
  record Week(LocalDate start, BigDecimal value, BigDecimal value2, String source, String note) {}

  /** The weeks from the goal's first week (at most the last 12) to this one, oldest first. */
  private List<Week> weeks(PersonScope p, ObjectNode g) {
    LocalDate thisWeek = weekOf(today());
    LocalDate created = weekOf(LocalDate.ofInstant(instant(g.path("createdAt").asText()), VitaRecordsUseCase.ZONE));
    LocalDate first = created.isBefore(thisWeek.minusWeeks(WEEKS_SHOWN - 1)) ? thisWeek.minusWeeks(WEEKS_SHOWN - 1) : created;
    Map<LocalDate, ObjectNode> typed = new HashMap<>();
    records.list(CHECKINS, p.owner(), Map.of("goalId", g.path("id").asText()))
        .forEach(c -> typed.put(LocalDate.parse(c.path("weekStart").asText()), c));
    // Typed check-ins from before the goal's first shown week still count.
    for (LocalDate d : typed.keySet()) if (d.isBefore(first) && !d.isBefore(thisWeek.minusWeeks(WEEKS_SHOWN - 1))) first = d;
    Map<LocalDate, BigDecimal[]> measured = measured(p, g, first, thisWeek.plusDays(6));
    List<Week> out = new ArrayList<>();
    for (LocalDate w = first; !w.isAfter(thisWeek); w = w.plusWeeks(1)) {
      ObjectNode c = typed.get(w);
      if (c != null) {
        out.add(new Week(w, dec(c, "value"), dec(c, "value2"), "manual", c.path("note").asText(null)));
      } else if (measured.containsKey(w)) {
        BigDecimal[] m = measured.get(w);
        out.add(new Week(w, m[0], m[1], "measurements", null));
      } else {
        out.add(new Week(w, null, null, "none", null));
      }
    }
    return out;
  }

  /** Weekly values from the person's own records, averaged unrounded and rounded for display. */
  private Map<LocalDate, BigDecimal[]> measured(PersonScope p, ObjectNode g, LocalDate from, LocalDate to) {
    String kind = g.path("kind").asText();
    Map<LocalDate, BigDecimal[]> out = new TreeMap<>();
    ZoneId zone = VitaRecordsUseCase.ZONE;
    switch (kind) {
      case "weight", "bp_range", "glucose_range" -> {
        String mk = switch (kind) { case "weight" -> "weight"; case "bp_range" -> "bp"; default -> "glucose"; };
        String ctx = g.path("context").asText(null);
        Map<LocalDate, List<BigDecimal[]>> byWeek = new TreeMap<>();
        for (ObjectNode m : measurements(p, mk)) {
          if ("glucose".equals(mk)) {
            String c = m.path("context").asText(null);
            if ("fasting".equals(c)) c = "before_meal";
            if (ctx == null || !ctx.equals(c)) continue;
          }
          LocalDate day = day(m.path("takenAt").asText(null), zone);
          if (day == null || day.isBefore(from) || day.isAfter(to)) continue;
          byWeek.computeIfAbsent(weekOf(day), k -> new ArrayList<>()).add(new BigDecimal[] {dec(m, "value"), dec(m, "value2")});
        }
        byWeek.forEach((w, list) -> out.put(w, new BigDecimal[] {avg(list, 0), "bp".equals(mk) ? avg(list, 1) : null}));
      }
      case "steps" -> {
        Map<LocalDate, BigDecimal> perDay = new TreeMap<>();
        for (ObjectNode m : measurements(p, "steps")) {
          LocalDate day = day(m.path("takenAt").asText(m.path("startedAt").asText(null)), zone);
          if (day == null || day.isBefore(from) || day.isAfter(to) || dec(m, "value") == null) continue;
          perDay.merge(day, dec(m, "value"), BigDecimal::add);
        }
        Map<LocalDate, List<BigDecimal[]>> byWeek = new TreeMap<>();
        perDay.forEach((d, v) -> byWeek.computeIfAbsent(weekOf(d), k -> new ArrayList<>()).add(new BigDecimal[] {v, null}));
        byWeek.forEach((w, list) -> out.put(w, new BigDecimal[] {avg(list, 0).setScale(0, RoundingMode.HALF_UP), null}));
      }
      case "sleep_hours" -> {
        Map<LocalDate, List<BigDecimal[]>> byWeek = new TreeMap<>();
        for (ObjectNode m : measurements(p, "sleep")) {
          Instant a = instant(m.path("startedAt").asText(null));
          Instant b = instant(m.path("endedAt").asText(null));
          if (a == null || b == null || !b.isAfter(a)) continue;
          LocalDate day = LocalDate.ofInstant(b, zone);
          if (day.isBefore(from) || day.isAfter(to)) continue;
          BigDecimal hours = BigDecimal.valueOf(Duration.between(a, b).toMinutes()).divide(BigDecimal.valueOf(60), 6, RoundingMode.HALF_UP);
          byWeek.computeIfAbsent(weekOf(day), k -> new ArrayList<>()).add(new BigDecimal[] {hours, null});
        }
        byWeek.forEach((w, list) -> out.put(w, new BigDecimal[] {avg(list, 0), null}));
      }
      case "hba1c" -> {
        Map<String, LocalDate> reports = new HashMap<>();
        for (ObjectNode r : records.list("vita_lab_report", p.owner(), VitaRecordsUseCase.livePerson(p.personId()))) {
          if (!"confirmed".equals(r.path("status").asText()) || !r.hasNonNull("collectedOn")) continue;
          reports.put(r.path("id").asText(), LocalDate.parse(r.get("collectedOn").asText()));
        }
        Map<String, Object> where = new LinkedHashMap<>(VitaRecordsUseCase.livePerson(p.personId()));
        where.put("testCode", "hba1c");
        for (ObjectNode r : records.list("vita_lab_result", p.owner(), where)) {
          LocalDate day = reports.get(r.path("reportId").asText());
          if (day == null || day.isBefore(from) || day.isAfter(to) || !r.hasNonNull("valueCanonical")) continue;
          out.put(weekOf(day), new BigDecimal[] {r.get("valueCanonical").decimalValue(), null});
        }
      }
      default -> {
        // water and custom goals are typed each week
      }
    }
    return out;
  }

  private List<ObjectNode> measurements(PersonScope p, String kind) {
    Map<String, Object> where = new LinkedHashMap<>(VitaRecordsUseCase.livePerson(p.personId()));
    where.put("kind", kind);
    return records.list("vita_measurement", p.owner(), where);
  }

  /**
   * Whether a week meets the goal; null when there is nothing to compare. A medical goal meets
   * its target range; steps, sleep and water reach the number; weight reaches it from the side
   * the starting weight was on (the first week's value when none was known).
   */
  static Boolean meets(JsonNode g, RangeSet t, Week w, List<Week> all) {
    if (w.value() == null) return null;
    String kind = g.path("kind").asText();
    if (MEDICAL.contains(kind)) {
      if (t == null) return null;
      boolean a = t.value() == null || t.value().contains(w.value());
      boolean b = t.value2() == null || (w.value2() != null && t.value2().contains(w.value2()));
      return a && b;
    }
    if (!g.hasNonNull("target")) return null;
    BigDecimal target = g.get("target").decimalValue();
    if ("weight".equals(kind)) {
      BigDecimal start = g.hasNonNull("startValue") ? g.get("startValue").decimalValue()
          : all.stream().map(Week::value).filter(java.util.Objects::nonNull).findFirst().orElse(w.value());
      return start.compareTo(target) > 0 ? w.value().compareTo(target) <= 0 : w.value().compareTo(target) >= 0;
    }
    return w.value().compareTo(target) >= 0;
  }

  /** Weeks in a row, up to the latest week with a value, that meet the goal. */
  static int streak(JsonNode g, RangeSet t, List<Week> weeks) {
    int n = 0;
    boolean started = false;
    for (int i = weeks.size() - 1; i >= 0; i--) {
      Week w = weeks.get(i);
      if (w.value() == null && !started) continue;
      started = true;
      if (Boolean.TRUE.equals(meets(g, t, w, weeks))) n++; else break;
    }
    return n;
  }

  // ── Checks ──────────────────────────────────────────────────────────────────

  /**
   * A medical goal takes no figure, needs a target from the doctor or config, and a sugar goal
   * names before or after a meal. Weight, steps, sleep and water need the number aimed for.
   */
  private static void checkGoal(VitaTargetService.Context ctx, ObjectNode values, JsonNode current) {
    String kind = values.hasNonNull("kind") ? values.get("kind").asText() : current.path("kind").asText();
    if (MEDICAL.contains(kind)) {
      for (String f : List.of("target", "target2", "unit")) {
        if (values.hasNonNull(f)) {
          throw new ValidationException("A " + words(kind) + " goal reads the target from the doctor or "
              + "Vita's settings and keeps no figure of its own; leave out " + f + ".");
        }
      }
      values.remove("target");
      values.remove("target2");
      values.remove("unit");
      String context = values.has("context") ? values.path("context").asText(null)
          : current == null ? null : current.path("context").asText(null);
      if ("glucose_range".equals(kind) && context == null) throw new ValidationException("Pick before or after a meal (context).");
      if (!"glucose_range".equals(kind)) {
        if (values.hasNonNull("context")) throw new ValidationException("context is only for a sugar goal.");
        values.remove("context");
      }
      if (current == null && target(ctx, kind, context) == null) throw new ValidationException(NO_TARGET);
      return;
    }
    if (values.hasNonNull("context")) throw new ValidationException("context is only for a sugar goal.");
    if (values.hasNonNull("target2")) throw new ValidationException("target2 is only for a blood pressure goal.");
    BigDecimal target = values.has("target") ? (values.hasNonNull("target") ? values.get("target").decimalValue() : null)
        : current == null || !current.hasNonNull("target") ? null : current.get("target").decimalValue();
    if (!"custom".equals(kind) && (target == null || target.signum() <= 0)) {
      throw new ValidationException("Type the number you are aiming for (target).");
    }
    String title = values.has("title") ? values.path("title").asText(null) : current == null ? null : current.path("title").asText(null);
    if ("custom".equals(kind) && (title == null || title.isBlank())) throw new ValidationException("Name the goal (title).");
  }

  private static void defaultUnit(ObjectNode values) {
    String unit = switch (values.path("kind").asText()) {
      case "weight" -> "kg";
      case "steps" -> "steps a day";
      case "sleep_hours" -> "hours a night";
      default -> null;
    };
    if (unit != null) values.put("unit", unit);
  }

  private static String words(String kind) {
    return switch (kind) {
      case "bp_range" -> "blood pressure";
      case "glucose_range" -> "sugar";
      default -> "HbA1c";
    };
  }

  // ── Helpers ─────────────────────────────────────────────────────────────────

  private int metAfterWeeks(UUID owner) {
    return config.list(owner).stream().filter(i -> "goals.met_after_weeks".equals(i.key())).findFirst()
        .map(i -> i.value() == null ? 4 : i.value().path("weeks").asInt(4)).filter(n -> n > 0).orElse(4);
  }

  private List<ObjectNode> live(PersonScope p) {
    return records.list(TABLE, p.owner(), VitaRecordsUseCase.livePerson(p.personId()));
  }

  private ObjectNode row(PersonScope p, UUID id) {
    return records.get(TABLE, p.owner(), id)
        .filter(r -> p.personId().toString().equals(r.path("personId").asText()))
        .filter(r -> r.path("deletedAt").isNull())
        .orElseThrow(() -> new NotFoundException("Goal", id));
  }

  static LocalDate weekOf(LocalDate d) {
    return d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
  }

  private static LocalDate today() {
    return LocalDate.now(VitaRecordsUseCase.ZONE);
  }

  private static BigDecimal avg(List<BigDecimal[]> list, int i) {
    BigDecimal sum = BigDecimal.ZERO;
    int n = 0;
    for (BigDecimal[] v : list) {
      if (v[i] == null) continue;
      sum = sum.add(v[i]);
      n++;
    }
    return n == 0 ? null : sum.divide(BigDecimal.valueOf(n), 1, RoundingMode.HALF_UP);
  }

  private static BigDecimal dec(JsonNode r, String f) {
    return r.hasNonNull(f) ? r.get(f).decimalValue() : null;
  }

  private static void putNum(ObjectNode o, String f, BigDecimal v) {
    if (v == null) o.putNull(f); else o.put(f, v.stripTrailingZeros().scale() < 0 ? v.setScale(0) : v.stripTrailingZeros());
  }

  private static String plain(BigDecimal v) {
    return v.stripTrailingZeros().toPlainString();
  }

  private static LocalDate day(String at, ZoneId zone) {
    Instant i = instant(at);
    return i == null ? null : LocalDate.ofInstant(i, zone);
  }

  private static Instant instant(String s) {
    if (s == null || s.isBlank()) return null;
    try {
      return OffsetDateTime.parse(s).toInstant();
    } catch (RuntimeException e) {
      try {
        return Instant.parse(s);
      } catch (RuntimeException e2) {
        return null;
      }
    }
  }

  private static UUID uuid(JsonNode v) {
    try {
      return UUID.fromString(v.asText());
    } catch (IllegalArgumentException e) {
      throw new ValidationException("personId must be an id.");
    }
  }

  private static Map<String, VitaField> fields(VitaField... fs) {
    Map<String, VitaField> m = new LinkedHashMap<>();
    for (VitaField f : fs) m.put(f.name(), f);
    return java.util.Collections.unmodifiableMap(m);
  }
}
