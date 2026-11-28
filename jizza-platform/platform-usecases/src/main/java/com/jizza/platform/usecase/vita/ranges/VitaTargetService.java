package com.jizza.platform.usecase.vita.ranges;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.vita.VitaRecords;
import com.jizza.platform.usecase.vita.config.VitaConfigService;
import com.jizza.platform.usecase.vita.config.VitaConfigService.ConfigItem;
import com.jizza.platform.usecase.vita.records.VitaRecordsUseCase;
import com.jizza.platform.usecase.vita.records.VitaTable;
import com.jizza.platform.usecase.vita.sharing.VitaScopeResolver;
import com.jizza.platform.usecase.vita.sharing.VitaScopeResolver.PersonScope;
import com.jizza.platform.usecase.vita.ranges.TargetEvaluator.Evaluation;
import com.jizza.platform.usecase.vita.ranges.TargetEvaluator.Reading;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Which range applies to a reading, and its status (spec 2.4, 5.1). In this order: the doctor's
 * target for the person, else the person's own range, else the Vita default from config (the set
 * of the person's active condition template, or the general set), else "not set". Every number
 * comes from {@code vita_usual_range} or {@link VitaConfigService}; none is written here.
 */
@Service
public class VitaTargetService {

  /** The measures a range can be kept for, with the contexts and settings that matter. */
  public record Slot(String kind, String context, String setting) {}

  public static final List<Slot> SLOTS = List.of(
      new Slot("bp", null, "home"), new Slot("bp", null, "clinic"),
      new Slot("glucose", "before_meal", null), new Slot("glucose", "after_meal", null),
      new Slot("heart_rate", null, null), new Slot("spo2", null, null),
      new Slot("temperature", null, null), new Slot("weight", null, null));

  static final Set<String> KINDS = Set.of("bp", "glucose", "weight", "heart_rate", "spo2", "temperature",
      "resting_heart_rate");
  static final Set<String> CONTEXTS = Set.of("fasting", "before_meal", "after_meal", "random", "bedtime");
  /** A bound as typed: up to four digits with up to three decimals. An entry format, not a limit. */
  private static final Pattern BOUND = Pattern.compile("\\d{1,4}(\\.\\d{1,3})?");

  private final VitaRecords records;
  private final VitaScopeResolver scopes;
  private final VitaConfigService config;
  private final VitaRecordsUseCase forms;
  private final AuditLog audit;
  private final com.jizza.platform.usecase.vita.labs.VitaLabCatalog labs;

  public VitaTargetService(VitaRecords records, VitaScopeResolver scopes, VitaConfigService config,
                           VitaRecordsUseCase forms, AuditLog audit,
                           com.jizza.platform.usecase.vita.labs.VitaLabCatalog labs) {
    this.records = records;
    this.scopes = scopes;
    this.config = config;
    this.forms = forms;
    this.audit = audit;
    this.labs = labs;
  }

  /**
   * Lab tests that are the same measure as a reading: a fasting glucose on a report is judged
   * against the before-meal sugar range, an after-food glucose against the after-meal one.
   */
  static final Map<String, String> LAB_AS_READING = Map.of("fasting_glucose", "before_meal", "pp_glucose", "after_meal");

  /** Everything evaluation needs for one person, read once. */
  public final class Context {
    final PersonScope person;
    final Map<String, ConfigItem> items = new LinkedHashMap<>();
    final List<ObjectNode> ranges;
    final Set<String> templates;
    final Map<UUID, String> doctors = new LinkedHashMap<>();
    public final List<RedFlagRule> rules = new ArrayList<>();

    Context(PersonScope p) {
      this.person = p;
      config.list(p.owner()).forEach(i -> items.put(i.key(), i));
      this.ranges = records.list("vita_usual_range", p.owner(), VitaRecordsUseCase.livePerson(p.personId()));
      this.templates = new java.util.HashSet<>();
      forms.live(p, VitaTable.CONDITION).stream().filter(c -> "active".equals(c.path("status").asText()))
          .forEach(c -> templates.add(c.path("template").asText("generic")));
      for (ObjectNode d : records.list("vita_doctor", p.owner(), Map.of())) {
        doctors.put(UUID.fromString(d.path("id").asText()), d.path("name").asText());
      }
      items.values().stream().filter(i -> i.key().startsWith("clinical.redflags.") && i.value() != null)
          .map(i -> RedFlagRule.fromConfig(i.key().substring("clinical.redflags.".length()), i.value(),
              i.needsVerification()))
          .filter(Objects::nonNull).forEach(rules::add);
    }

    public Evaluation evaluate(Reading r, Boolean signsNow) {
      return TargetEvaluator.evaluate(r, rangeFor(r.kind(), r.context(), r.setting()), rules, signsNow);
    }

    /**
     * The target for a lab test (spec 2.4): the doctor's target for the test, else the person's own
     * range for it, else, for a test that is a sugar reading, the range for that reading, else the
     * Vita default of the person's active condition template or the general set. Null when none
     * holds a number. Bounds are in the test's canonical unit.
     */
    public RangeSet labRangeFor(String testCode) {
      if (testCode == null) return null;
      ObjectNode doctor = bestLab(testCode, "doctor");
      if (doctor != null) {
        UUID did = doctor.hasNonNull("doctorId") ? UUID.fromString(doctor.get("doctorId").asText()) : null;
        return RangeSet.doctor(range(doctor, ""), null, did, doctors.get(did),
            doctor.hasNonNull("setOn") ? LocalDate.parse(doctor.get("setOn").asText()) : null);
      }
      ObjectNode own = bestLab(testCode, "user");
      if (own != null) return RangeSet.own(range(own, ""), null);
      if (LAB_AS_READING.containsKey(testCode)) return rangeFor("glucose", LAB_AS_READING.get(testCode), null);
      List<String> keys = new ArrayList<>();
      for (String t : List.of("diabetes_t2", "hypertension")) {
        if (templates.contains(t)) keys.add("clinical.ranges." + t + "." + testCode);
      }
      keys.add("clinical.ranges.general." + testCode);
      for (String key : keys) {
        ConfigItem item = items.get(key);
        for (int hops = 0; item != null && item.value() != null && item.value().hasNonNull("sameAs") && hops < 3; hops++) {
          item = items.get(item.value().get("sameAs").asText());
        }
        if (item == null || item.value() == null) continue;
        Range r = Range.fromConfig(item.value());
        if (r != null) return RangeSet.vitaDefault(r, null, item.key(), item.needsVerification());
      }
      return null;
    }

    private ObjectNode bestLab(String code, String setBy) {
      for (ObjectNode r : ranges) {
        if (code.equals(r.path("labTestCode").asText(null)) && setBy.equals(r.path("setBy").asText())) return r;
      }
      return null;
    }

    /** The range in force for a measure, or null when none is set. */
    public RangeSet rangeFor(String kind, String context, String setting) {
      String ctx = "fasting".equals(context) ? "before_meal" : context;
      String set = "bp".equals(kind) && setting == null ? "home" : setting;
      ObjectNode doctor = best(kind, ctx, set, "doctor");
      if (doctor != null) {
        UUID did = doctor.hasNonNull("doctorId") ? UUID.fromString(doctor.get("doctorId").asText()) : null;
        return RangeSet.doctor(range(doctor, ""), range(doctor, "2"), did, doctors.get(did),
            doctor.hasNonNull("setOn") ? LocalDate.parse(doctor.get("setOn").asText()) : null);
      }
      ObjectNode own = best(kind, ctx, set, "user");
      if (own != null) return RangeSet.own(range(own, ""), range(own, "2"));
      return vitaDefault(kind, ctx, set);
    }

    /** The most specific live row for the measure: exact context and setting beat an empty one. */
    private ObjectNode best(String kind, String ctx, String set, String setBy) {
      ObjectNode best = null;
      int bestScore = -1;
      for (ObjectNode r : ranges) {
        if (!kind.equals(r.path("kind").asText(null)) || !setBy.equals(r.path("setBy").asText())) continue;
        String rc = r.hasNonNull("context") ? r.get("context").asText() : null;
        if ("fasting".equals(rc)) rc = "before_meal";
        String rs = r.hasNonNull("setting") ? r.get("setting").asText() : null;
        if (rc != null && !rc.equals(ctx)) continue;
        if (rs != null && !rs.equals(set)) continue;
        int score = (rc != null ? 2 : 0) + (rs != null ? 1 : 0);
        if (score > bestScore) { best = r; bestScore = score; }
      }
      return best;
    }

    private RangeSet vitaDefault(String kind, String ctx, String set) {
      String key = switch (kind) {
        case "bp" -> (templates.contains("hypertension") ? "clinical.ranges.hypertension.bp_" : "clinical.ranges.general.bp_") + set;
        case "glucose" -> {
          String suffix = "before_meal".equals(ctx) ? "glucose_before_meal" : "after_meal".equals(ctx) ? "glucose_after_meal" : null;
          if (suffix == null) yield null;
          yield templates.contains("diabetes_t2") && items.containsKey("clinical.ranges.diabetes_t2." + suffix)
              ? "clinical.ranges.diabetes_t2." + suffix
              : "clinical.ranges.general." + ("glucose_before_meal".equals(suffix) ? "glucose_fasting" : "glucose_after_meal");
        }
        default -> "clinical.ranges.general." + kind;
      };
      if (key == null) return null;
      ConfigItem item = items.get(key);
      for (int hops = 0; item != null && item.value() != null && item.value().hasNonNull("sameAs") && hops < 3; hops++) {
        item = items.get(item.value().get("sameAs").asText());
      }
      if (item == null || item.value() == null) return null;
      JsonNode v = item.value();
      Range a = "bp".equals(kind) ? Range.fromConfig(v.get("systolic")) : Range.fromConfig(v);
      Range b = "bp".equals(kind) ? Range.fromConfig(v.get("diastolic")) : null;
      if (a == null && b == null) return null;
      return RangeSet.vitaDefault(a, b, item.key(), item.needsVerification());
    }
  }

  public Context context(PersonScope p) {
    return new Context(p);
  }

  /** A range from a vita_usual_range row; suffix "" for the first value, "2" for the diastolic. */
  static Range range(ObjectNode row, String suffix) {
    BigDecimal lo = row.hasNonNull("low" + suffix) ? row.get("low" + suffix).decimalValue() : null;
    BigDecimal hi = row.hasNonNull("high" + suffix) ? row.get("high" + suffix).decimalValue() : null;
    if (lo == null && hi == null) return null;
    return new Range(lo, row.path("lowInclusive").asBoolean(true), hi, row.path("highInclusive").asBoolean(true));
  }

  // ── The ranges screen ───────────────────────────────────────────────────────

  /** One measure on the ranges screen: what applies now, and the rows behind it. */
  public record RangeView(String kind, String context, String setting, String status, String range,
                          String source, String label, boolean needsVerification, UUID doctorId,
                          String doctorName, LocalDate setOn, ObjectNode own, ObjectNode doctorTarget,
                          String labTestCode) {}

  public List<RangeView> ranges(UUID caller, UUID personId) {
    PersonScope p = scopes.person(caller, personId, com.jizza.platform.usecase.vita.sharing.VitaSection.VITALS, false);
    Context ctx = context(p);
    List<RangeView> out = new ArrayList<>();
    for (Slot s : SLOTS) {
      RangeSet r = ctx.rangeFor(s.kind(), s.context(), s.setting());
      ObjectNode own = ctx.best(s.kind(), s.context(), s.setting(), "user");
      ObjectNode doc = ctx.best(s.kind(), s.context(), s.setting(), "doctor");
      if (r == null) {
        out.add(new RangeView(s.kind(), s.context(), s.setting(), TargetEvaluator.NOT_SET, null, null,
            TargetEvaluator.NOT_SET_LABEL, false, null, null, null, own, doc, null));
      } else {
        out.add(new RangeView(s.kind(), s.context(), s.setting(), "set", r.text(), r.source(), r.label(),
            r.needsVerification(), r.doctorId(), r.doctorName(), r.setOn(), own, doc, null));
      }
    }
    // Lab tests: HbA1c always, and every other test the person or a doctor set a range for.
    java.util.LinkedHashSet<String> codes = new java.util.LinkedHashSet<>(List.of("hba1c"));
    ctx.ranges.stream().map(r -> r.path("labTestCode").asText(null)).filter(Objects::nonNull).forEach(codes::add);
    for (String code : codes) {
      RangeSet r = ctx.labRangeFor(code);
      ObjectNode own = ctx.bestLab(code, "user");
      ObjectNode doc = ctx.bestLab(code, "doctor");
      if (r == null) {
        out.add(new RangeView(null, null, null, TargetEvaluator.NOT_SET, null, null, TargetEvaluator.NOT_SET_LABEL,
            false, null, null, null, own, doc, code));
      } else {
        out.add(new RangeView(null, null, null, "set", r.text(), r.source(), r.label(), r.needsVerification(),
            r.doctorId(), r.doctorName(), r.setOn(), own, doc, code));
      }
    }
    return out;
  }

  /**
   * Sets the person's own range or a doctor's target for one measure, replacing the live row for
   * the same measure, context, setting and setter.
   */
  @Transactional
  public ObjectNode putRange(UUID caller, UUID personId, JsonNode body) {
    PersonScope p = scopes.person(caller, personId).requireOpen();
    if (body == null || !body.isObject()) throw new ValidationException("Send the range as an object.");
    Set<String> allowed = Set.of("kind", "labTestCode", "context", "setting", "low", "high", "low2", "high2",
        "lowInclusive", "highInclusive", "setBy", "doctorId", "setOn", "note");
    body.fieldNames().forEachRemaining(f -> {
      if (!allowed.contains(f)) throw new ValidationException(f + " cannot be set here.");
    });
    var n = JsonNodeFactory.instance;
    ObjectNode v = n.objectNode();
    String labTestCode = text(body, "labTestCode");
    String kind = labTestCode != null ? "" : body.path("kind").asText("");
    if (labTestCode != null) {
      if (body.hasNonNull("kind")) throw new ValidationException("Give kind or labTestCode, not both.");
      if (!labs.known(labTestCode)) throw new ValidationException("labTestCode is not a known lab test.");
      v.putNull("kind");
      v.put("labTestCode", labTestCode);
    } else {
      if (!KINDS.contains(kind)) throw new ValidationException("kind must be one of " + String.join(", ", KINDS) + ".");
      v.put("kind", kind);
    }
    String context = text(body, "context");
    if (context != null && !CONTEXTS.contains(context)) throw new ValidationException("context must be one of " + String.join(", ", CONTEXTS) + ".");
    if (context != null && !"glucose".equals(kind)) throw new ValidationException("Only sugar readings have a context.");
    String setting = text(body, "setting");
    if (setting != null && !Set.of("home", "clinic").contains(setting)) throw new ValidationException("setting must be home or clinic.");
    if (setting != null && !"bp".equals(kind)) throw new ValidationException("Only blood pressure has a home or clinic setting.");
    if (context == null) v.putNull("context"); else v.put("context", context);
    if (setting == null) v.putNull("setting"); else v.put("setting", setting);
    for (String f : List.of("low", "high", "low2", "high2")) {
      BigDecimal b = bound(body, f);
      if (b == null) v.putNull(f); else v.put(f, b);
    }
    if (!"bp".equals(kind) && (v.hasNonNull("low2") || v.hasNonNull("high2"))) {
      throw new ValidationException("Only blood pressure has a second value.");
    }
    if (!v.hasNonNull("low") && !v.hasNonNull("high") && !v.hasNonNull("low2") && !v.hasNonNull("high2")) {
      throw new ValidationException("Give at least one bound.");
    }
    order(v, "low", "high");
    order(v, "low2", "high2");
    v.put("lowInclusive", body.path("lowInclusive").asBoolean(true));
    v.put("highInclusive", body.path("highInclusive").asBoolean(true));
    String setBy = body.path("setBy").asText("user");
    if (!Set.of("user", "doctor").contains(setBy)) throw new ValidationException("setBy must be user or doctor.");
    v.put("setBy", setBy);
    if ("doctor".equals(setBy)) {
      if (!body.hasNonNull("doctorId")) throw new ValidationException("Name the doctor who set this target.");
      v.put("doctorId", body.get("doctorId").asText());
      forms.checkRefs(p.owner(), Map.of("doctorId", com.jizza.platform.usecase.vita.records.VitaField.ref("doctorId", "vita_doctor")), v);
      v.put("setOn", body.hasNonNull("setOn") ? VitaRecordsUseCase.pastDate("setOn", body.get("setOn")).toString()
          : LocalDate.now(VitaRecordsUseCase.ZONE).toString());
    } else {
      v.putNull("doctorId");
      v.put("setOn", LocalDate.now(VitaRecordsUseCase.ZONE).toString());
    }
    String note = text(body, "note");
    if (note != null && note.length() > 500) throw new ValidationException("note is too long (500 at most).");
    if (note == null) v.putNull("note"); else v.put("note", note);

    // Replace the live row for the same measure and setter.
    for (ObjectNode r : records.list("vita_usual_range", p.owner(), VitaRecordsUseCase.livePerson(p.personId()))) {
      boolean same = labTestCode != null ? labTestCode.equals(r.path("labTestCode").asText(null))
          : kind.equals(r.path("kind").asText(null));
      if (same && setBy.equals(r.path("setBy").asText())
          && Objects.equals(context, r.hasNonNull("context") ? r.get("context").asText() : null)
          && Objects.equals(setting, r.hasNonNull("setting") ? r.get("setting").asText() : null)) {
        forms.removeRow(p, "vita_usual_range", "Range", UUID.fromString(r.path("id").asText()));
      }
    }
    v.put("personId", p.personId().toString());
    v.put("createdByUserId", caller.toString());
    ObjectNode row = records.insert("vita_usual_range", p.owner(), v);
    audit.record(p.owner(), ActorType.USER, caller, "vita.range.set", row.path("id").asText(), null);
    return row;
  }

  @Transactional
  public void deleteRange(UUID caller, UUID personId, UUID id) {
    PersonScope p = scopes.person(caller, personId).requireOpen();
    forms.removeRow(p, "vita_usual_range", "Range", id);
    audit.record(p.owner(), ActorType.USER, caller, "vita.range.removed", id.toString(), null);
  }

  /** The targets a doctor set, across the household, for the doctors list. */
  public List<ObjectNode> targetsBy(UUID owner, UUID doctorId) {
    Map<String, Object> where = new LinkedHashMap<>();
    where.put("doctorId", doctorId);
    where.put("deletedAt", null);
    return records.list("vita_usual_range", owner, where);
  }

  private static void order(ObjectNode v, String lo, String hi) {
    if (v.hasNonNull(lo) && v.hasNonNull(hi) && v.get(lo).decimalValue().compareTo(v.get(hi).decimalValue()) > 0) {
      throw new ValidationException(lo + " must not be above " + hi + ".");
    }
  }

  private static BigDecimal bound(JsonNode body, String f) {
    if (!body.hasNonNull(f)) return null;
    JsonNode x = body.get(f);
    String s = x.isNumber() ? x.decimalValue().stripTrailingZeros().toPlainString() : x.asText().strip();
    if (!BOUND.matcher(s).matches()) throw new ValidationException(f + " must be a number such as 140 or 7.5.");
    return new BigDecimal(s);
  }

  private static String text(JsonNode body, String f) {
    return body.hasNonNull(f) && !body.get(f).asText().isBlank() ? body.get(f).asText().strip() : null;
  }

}
