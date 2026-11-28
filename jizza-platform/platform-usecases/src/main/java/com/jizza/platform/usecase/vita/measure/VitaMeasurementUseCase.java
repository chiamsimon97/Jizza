package com.jizza.platform.usecase.vita.measure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.error.ConflictException;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.vita.VitaRecords;
import com.jizza.platform.usecase.vita.ranges.TargetEvaluator;
import com.jizza.platform.usecase.vita.ranges.TargetEvaluator.Evaluation;
import com.jizza.platform.usecase.vita.ranges.TargetEvaluator.Reading;
import com.jizza.platform.usecase.vita.ranges.VitaTargetService;
import com.jizza.platform.usecase.vita.records.VitaRecordsUseCase;
import com.jizza.platform.usecase.vita.sharing.VitaScopeResolver;
import com.jizza.platform.usecase.vita.sharing.VitaScopeResolver.PersonScope;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Readings (spec 4.2 {@code vita_measurement}, 5.1, 5.12): add, correct, soft delete, move, the
 * second reading and the duplicate check. Values are stored in canonical units (mmHg, mg/dL, kg,
 * °C). A typed value must pass its kind's entry format before it is saved or evaluated, so a slip
 * such as 1820 is refused and never raises an alarm; no measuring-range limit is set
 * ({@code clinical.entry_limits} is not set today).
 *
 * <p>Every reading comes back with its evaluation: target status, the range used and who set it,
 * and a red flag with its level. A typed reading that raises a red flag is confirmed by the user
 * ({@link VitaRedFlagService#confirm}) before anyone is alerted.
 */
@Service
public class VitaMeasurementUseCase {

  static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
  /** The kinds a person types. Steps, sleep and the rest come from a connected phone (Phase 3). */
  public static final Set<String> KINDS = Set.of("bp", "glucose", "weight", "heart_rate", "spo2", "temperature");
  /** Every kind the table holds, for reading lists. */
  public static final Set<String> ALL_KINDS = Set.of("bp", "glucose", "weight", "heart_rate", "spo2",
      "temperature", "steps", "sleep", "resting_heart_rate", "workout");
  public static final Set<String> CONTEXTS = Set.of("fasting", "before_meal", "after_meal", "random", "bedtime");
  public static final Set<String> MEALS = Set.of("breakfast", "lunch", "dinner");
  /** A pound by definition, in kilograms: a unit conversion, not a clinical figure. */
  static final BigDecimal KG_PER_LB = new BigDecimal("0.45359237");
  /** Weight as typed: one to three digits, with up to two decimals. */
  private static final Pattern WEIGHT = Pattern.compile("\\d{1,3}(\\.\\d{1,2})?");
  /** Blood pressure, pulse, heart rate and SpO2: two or three digits. */
  private static final Pattern WHOLE_2_3 = Pattern.compile("\\d{2,3}");
  /** Sugar in mg/dL: two or three digits, with up to one decimal. */
  private static final Pattern SUGAR = Pattern.compile("\\d{2,3}(\\.\\d)?");
  /** Temperature: two or three digits with up to one decimal. */
  private static final Pattern TEMP = Pattern.compile("\\d{2,3}(\\.\\d)?");
  private static final Set<String> FIELDS = Set.of("kind", "value", "value2", "value3", "unit", "context",
      "meal", "setting", "takenAt", "note");
  private static final int MAX_NOTE = 500;
  private static final String TABLE = "vita_measurement";

  private final VitaRecords records;
  private final VitaScopeResolver scopes;
  private final VitaRecordsUseCase forms;
  private final VitaTargetService targets;
  private final VitaRedFlagService redFlags;
  private final AuditLog audit;

  public VitaMeasurementUseCase(VitaRecords records, VitaScopeResolver scopes, VitaRecordsUseCase forms,
                                VitaTargetService targets, VitaRedFlagService redFlags, AuditLog audit) {
    this.records = records;
    this.scopes = scopes;
    this.forms = forms;
    this.targets = targets;
    this.redFlags = redFlags;
    this.audit = audit;
  }

  /** What a new reading may carry besides its fields. */
  public record Options(UUID secondOf, boolean confirmDuplicate) {
    public static final Options NONE = new Options(null, false);
  }

  /** The person's live readings, newest first, each with its evaluation; {@code kind} narrows the list. */
  public List<ObjectNode> list(UUID caller, UUID personId, String kind) {
    PersonScope p = scopes.person(caller, personId, com.jizza.platform.usecase.vita.sharing.VitaSection.VITALS, false);
    var ctx = targets.context(p);
    return list(p, kind).stream().map(r -> withEvaluation(r, ctx)).toList();
  }

  public List<ObjectNode> list(PersonScope p, String kind) {
    var where = VitaRecordsUseCase.livePerson(p.personId());
    if (kind != null && !kind.isBlank()) {
      if (!ALL_KINDS.contains(kind)) throw new ValidationException("kind must be one of " + String.join(", ", KINDS) + ".");
      where.put("kind", kind);
    }
    return records.list(TABLE, p.owner(), where).stream()
        .sorted(Comparator.comparing((ObjectNode r) -> r.path("takenAt").asText("")).reversed()).toList();
  }

  public Optional<ObjectNode> latest(PersonScope p, String kind) {
    return list(p, kind).stream().findFirst();
  }

  public ObjectNode get(UUID caller, UUID personId, UUID id) {
    PersonScope p = scopes.person(caller, personId, com.jizza.platform.usecase.vita.sharing.VitaSection.VITALS, false);
    return withEvaluation(get(p, id), targets.context(p));
  }

  @Transactional
  public ObjectNode create(UUID caller, UUID personId, JsonNode body, Options options) {
    return create(scopes.person(caller, personId, com.jizza.platform.usecase.vita.sharing.VitaSection.VITALS, true), body, "manual", options);
  }

  @Transactional
  public ObjectNode create(PersonScope p, JsonNode body, String source) {
    return create(p, body, source, Options.NONE);
  }

  /**
   * Adds a reading. The same values for the same person within the duplicate window are refused
   * with "Already saved. Add anyway?" unless {@code confirmDuplicate}; a duplicate from a device or
   * import is dropped and the saved row comes back with {@code duplicateOf}.
   */
  @Transactional
  public ObjectNode create(PersonScope p, JsonNode body, String source, Options options) {
    p.requireOpen();
    ObjectNode values = clean(body, true);
    if (!values.hasNonNull("takenAt")) values.put("takenAt", Instant.now().toString());
    String src = VitaRecordsUseCase.SOURCES.contains(source) ? source : "manual";
    Optional<ObjectNode> twin = duplicate(p, values);
    if (twin.isPresent() && !options.confirmDuplicate()) {
      if (!"manual".equals(src) && !"chat".equals(src) && !"quick_start".equals(src)) {
        ObjectNode kept = twin.get().deepCopy();
        kept.put("duplicateOf", kept.path("id").asText());
        return withEvaluation(kept, targets.context(p));
      }
      throw new ConflictException("Already saved. Add anyway? The same reading was saved at "
          + twin.get().path("takenAt").asText() + " (id " + twin.get().path("id").asText() + ").");
    }
    if (options.secondOf() != null) {
      ObjectNode first = get(p, options.secondOf());
      if (!first.path("kind").asText().equals(values.path("kind").asText())) {
        throw new ValidationException("A second reading must be of the same kind as the first.");
      }
      String pair = first.hasNonNull("pairId") ? first.get("pairId").asText() : first.path("id").asText();
      if (!first.hasNonNull("pairId")) {
        records.update(TABLE, p.owner(), options.secondOf(), Json.obj().put("pairId", pair));
      }
      values.put("pairId", pair);
    }
    values.put("personId", p.personId().toString());
    values.put("source", src);
    values.put("createdByUserId", p.caller().toString());
    ObjectNode row = records.insert(TABLE, p.owner(), values);
    audit.record(p.owner(), ActorType.USER, p.caller(), "vita.measurement.added", row.path("id").asText(), null);
    ObjectNode out = withEvaluation(row, targets.context(p));
    if (out.path("evaluation").path("status").asText().equals(TargetEvaluator.RED_FLAG)) {
      boolean typed = Set.of("manual", "chat", "quick_start").contains(src);
      if (typed) {
        // Spec 5.1, Fixture B: the user confirms a typed value before anyone is alerted.
        out.put("confirmNeeded", true);
        out.put("confirmQuestion", "Is " + shown(row) + " right?");
      } else {
        redFlags.alert(p, row, evaluationOf(row, targets.context(p), null));
        out.put("alerted", true);
      }
    }
    return out;
  }

  /** Corrects a reading: the fields sent replace what was there. */
  @Transactional
  public ObjectNode update(UUID caller, UUID personId, UUID id, JsonNode body) {
    PersonScope p = scopes.person(caller, personId, com.jizza.platform.usecase.vita.sharing.VitaSection.VITALS, true).requireOpen();
    ObjectNode current = get(p, id);
    VitaRecordsUseCase.requireOwnEntry(p, current, "Reading", id);
    if (body == null || !body.isObject()) throw new ValidationException("Send the fields as an object.");
    ObjectNode merged = current.deepCopy();
    merged.retain(FIELDS);
    if ("weight".equals(current.path("kind").asText())) merged.put("unit", "kg");
    if ("temperature".equals(current.path("kind").asText())) merged.put("unit", "C");
    body.fields().forEachRemaining(e -> merged.set(e.getKey(), e.getValue()));
    if (body.has("kind") && !body.path("kind").asText().equals(current.path("kind").asText())) {
      throw new ValidationException("A reading's kind cannot change; add a new reading instead.");
    }
    ObjectNode values = clean(merged, false);
    values.remove("kind");
    ObjectNode row = records.update(TABLE, p.owner(), id, values)
        .orElseThrow(() -> new NotFoundException("Reading", id));
    audit.record(p.owner(), ActorType.USER, caller, "vita.measurement.updated", id.toString(), null);
    return withEvaluation(row, targets.context(p));
  }

  @Transactional
  public void delete(UUID caller, UUID personId, UUID id) {
    PersonScope p = scopes.person(caller, personId, com.jizza.platform.usecase.vita.sharing.VitaSection.VITALS, true).requireOpen().requireManager();
    forms.removeRow(p, TABLE, "Reading", id);
    audit.record(p.owner(), ActorType.USER, caller, "vita.measurement.removed", id.toString(), null);
  }

  /** Moves a reading entered for the wrong person to another person; it keeps its time and source. */
  @Transactional
  public ObjectNode move(UUID caller, UUID personId, UUID id, UUID toPersonId) {
    PersonScope from = scopes.person(caller, personId).requireOpen();
    get(from, id);
    PersonScope to = scopes.person(caller, toPersonId).requireOpen();
    ObjectNode row = records.update(TABLE, from.owner(), id,
            JsonNodeFactory.instance.objectNode().put("personId", to.personId().toString()).putNull("pairId"))
        .orElseThrow(() -> new NotFoundException("Reading", id));
    audit.record(from.owner(), ActorType.USER, caller, "vita.measurement.moved", id.toString(), null);
    return withEvaluation(row, targets.context(to));
  }

  public ObjectNode get(PersonScope p, UUID id) {
    return records.get(TABLE, p.owner(), id)
        .filter(r -> p.personId().toString().equals(r.path("personId").asText()))
        .filter(r -> r.path("deletedAt").isNull())
        .orElseThrow(() -> new NotFoundException("Reading", id));
  }

  /** The same values for the same person and kind within the duplicate window. */
  Optional<ObjectNode> duplicate(PersonScope p, ObjectNode v) {
    Instant at = Instant.parse(v.get("takenAt").asText());
    Duration window = Duration.ofMinutes(redFlags.duplicateWindowMinutes(p.owner()));
    var where = VitaRecordsUseCase.livePerson(p.personId());
    where.put("kind", v.get("kind").asText());
    return records.list(TABLE, p.owner(), where).stream()
        .filter(r -> same(r, v, "value") && same(r, v, "value2"))
        .filter(r -> r.hasNonNull("takenAt")
            && Duration.between(Instant.parse(r.get("takenAt").asText()), at).abs().compareTo(window) <= 0)
        .findFirst();
  }

  private static boolean same(ObjectNode a, ObjectNode b, String f) {
    BigDecimal x = a.hasNonNull(f) ? a.get(f).decimalValue() : null;
    BigDecimal y = b.hasNonNull(f) ? b.get(f).decimalValue() : null;
    return x == null ? y == null : y != null && x.compareTo(y) == 0;
  }

  /** The row with its evaluation attached under {@code evaluation}. */
  public static ObjectNode withEvaluation(ObjectNode row, VitaTargetService.Context ctx) {
    Evaluation e = evaluationOf(row, ctx, null);
    ObjectNode out = row.deepCopy();
    if (e != null) out.set("evaluation", Json.toNode(e));
    return out;
  }

  public static Evaluation evaluationOf(ObjectNode row, VitaTargetService.Context ctx, Boolean signsNow) {
    Reading r = readingOf(row);
    return r == null ? null : ctx.evaluate(r, signsNow);
  }

  public static Reading readingOf(ObjectNode row) {
    String kind = row.path("kind").asText();
    if (!Set.of("bp", "glucose", "weight", "heart_rate", "spo2", "temperature", "resting_heart_rate").contains(kind)) {
      return null;
    }
    return new Reading(kind, dec(row, "value"), dec(row, "value2"), text(row, "context"), text(row, "setting"));
  }

  /** "182/112" for a BP reading, the number and unit otherwise. */
  public static String shown(ObjectNode row) {
    String v = plain(dec(row, "value"));
    if ("bp".equals(row.path("kind").asText())) return v + "/" + plain(dec(row, "value2"));
    return v + " " + row.path("unit").asText("");
  }

  private static String plain(BigDecimal d) {
    return d == null ? "" : d.stripTrailingZeros().toPlainString();
  }

  private static BigDecimal dec(ObjectNode r, String f) {
    return r.hasNonNull(f) ? r.get(f).decimalValue() : null;
  }

  private static String text(ObjectNode r, String f) {
    return r.hasNonNull(f) ? r.get(f).asText() : null;
  }

  /** Checks a new reading as typed, throwing what is wrong; nothing is saved (chat forms). */
  public static void check(JsonNode typed) {
    clean(typed, true);
  }

  /** Checks a reading's fields against its kind's entry format; values are stored canonically. */
  static ObjectNode clean(JsonNode body, boolean creating) {
    if (body == null || !body.isObject()) throw new ValidationException("Send the fields as an object.");
    Iterator<String> names = body.fieldNames();
    while (names.hasNext()) {
      String f = names.next();
      if (!FIELDS.contains(f)) throw new ValidationException(f + " cannot be set here.");
    }
    var n = JsonNodeFactory.instance;
    ObjectNode out = n.objectNode();
    String kind = body.path("kind").asText("");
    if (!KINDS.contains(kind)) {
      throw new ValidationException(ALL_KINDS.contains(kind)
          ? kind + " comes from a connected phone, not typed."
          : "kind must be one of " + String.join(", ", KINDS.stream().sorted().toList()) + ".");
    }
    out.put("kind", kind);
    String unit = body.hasNonNull("unit") ? body.get("unit").asText() : null;
    switch (kind) {
      case "bp" -> {
        if (unit != null && !"mmHg".equals(unit)) throw new ValidationException("unit must be mmHg.");
        out.put("value", whole(body.get("value"), "systolic"));
        out.put("value2", whole(body.get("value2"), "diastolic"));
        if (body.hasNonNull("value3")) out.put("value3", whole(body.get("value3"), "pulse"));
        else out.putNull("value3");
        out.put("unit", "mmHg");
        String setting = body.hasNonNull("setting") ? body.get("setting").asText() : "home";
        if (!Set.of("home", "clinic").contains(setting)) throw new ValidationException("setting must be home or clinic.");
        out.put("setting", setting);
      }
      case "glucose" -> {
        if (unit != null && !"mg/dL".equals(unit)) {
          throw new ValidationException("Sugar is taken in mg/dL for now: the conversion from mmol/L is not set.");
        }
        out.put("value", matching(body.get("value"), SUGAR, "value must be a sugar reading such as 112 (mg/dL)."));
        out.put("unit", "mg/dL");
        String context = body.hasNonNull("context") ? body.get("context").asText() : "random";
        if (!CONTEXTS.contains(context)) throw new ValidationException("context must be one of " + String.join(", ", CONTEXTS) + ".");
        out.put("context", context);
        if (body.hasNonNull("meal")) {
          if (!MEALS.contains(body.get("meal").asText())) throw new ValidationException("meal must be breakfast, lunch or dinner.");
          out.put("meal", body.get("meal").asText());
        } else {
          out.putNull("meal");
        }
      }
      case "heart_rate", "spo2" -> {
        BigDecimal v = whole(body.get("value"), "value");
        if ("spo2".equals(kind) && v.compareTo(BigDecimal.valueOf(100)) > 0) {
          throw new ValidationException("SpO2 is a percentage: 100 at most.");
        }
        out.put("value", v);
        out.put("unit", "spo2".equals(kind) ? "%" : "bpm");
      }
      case "temperature" -> {
        String u = unit == null ? "C" : unit;
        if (!Set.of("C", "F").contains(u)) throw new ValidationException("unit must be C or F.");
        BigDecimal typed = matching(body.get("value"), TEMP, "value must be a temperature such as 37.2 or 99.1.");
        // °F to °C by definition of the scales: a unit conversion, not a clinical figure.
        BigDecimal c = "F".equals(u)
            ? typed.subtract(BigDecimal.valueOf(32)).multiply(BigDecimal.valueOf(5)).divide(BigDecimal.valueOf(9), 3, RoundingMode.HALF_UP)
            : typed;
        out.put("value", c);
        out.put("unit", "C");
        if ("F".equals(u)) out.set("detail", n.objectNode().put("typed", typed).put("typedUnit", "F"));
      }
      default -> { // weight
        if (body.has("value") || creating) {
          String u = unit == null ? "kg" : unit;
          if (!Set.of("kg", "lb").contains(u)) throw new ValidationException("unit must be kg or lb.");
          BigDecimal typed = weight(body.get("value"));
          BigDecimal kg = "lb".equals(u) ? typed.multiply(KG_PER_LB).setScale(3, RoundingMode.HALF_UP) : typed;
          out.put("value", kg);
          out.put("unit", "kg");
          if ("lb".equals(u)) out.set("detail", n.objectNode().put("typed", typed).put("typedUnit", "lb"));
          else out.putNull("detail");
        }
      }
    }
    if (body.has("takenAt")) {
      if (body.get("takenAt").isNull()) throw new ValidationException("takenAt is needed.");
      out.put("takenAt", takenAt(body.get("takenAt")).toString());
    }
    if (body.has("note")) {
      JsonNode v = body.get("note");
      if (v.isNull() || v.asText().isBlank()) out.putNull("note");
      else if (!v.isTextual() || v.asText().length() > MAX_NOTE) throw new ValidationException("note is too long (500 at most).");
      else out.put("note", v.asText().strip());
    }
    return out;
  }

  private static BigDecimal whole(JsonNode v, String name) {
    if (v == null || v.isNull()) throw new ValidationException(name + " is needed.");
    String s = v.isNumber() ? v.decimalValue().stripTrailingZeros().toPlainString() : v.asText().strip();
    if (!WHOLE_2_3.matcher(s).matches()) {
      throw new ValidationException(name + " must have two or three digits, such as 82 or 132.");
    }
    return new BigDecimal(s);
  }

  private static BigDecimal matching(JsonNode v, Pattern p, String message) {
    if (v == null || v.isNull()) throw new ValidationException("value is needed.");
    String s = v.isNumber() ? v.decimalValue().stripTrailingZeros().toPlainString() : v.asText().strip();
    if (!p.matcher(s).matches()) throw new ValidationException(message);
    return new BigDecimal(s);
  }

  static BigDecimal weight(JsonNode v) {
    if (v == null || v.isNull()) throw new ValidationException("value is needed.");
    String s = v.isNumber() ? v.decimalValue().stripTrailingZeros().toPlainString() : v.asText().strip();
    if (!WEIGHT.matcher(s).matches() || new BigDecimal(s).signum() <= 0) {
      throw new ValidationException("value must be a weight such as 64 or 64.5.");
    }
    return new BigDecimal(s);
  }

  /** An instant, or a local date-time in India; never more than five minutes ahead. */
  static Instant takenAt(JsonNode v) {
    String s = v.asText().strip();
    Instant at;
    try {
      at = OffsetDateTime.parse(s).toInstant();
    } catch (DateTimeParseException e) {
      try {
        at = Instant.parse(s);
      } catch (DateTimeParseException e2) {
        try {
          at = LocalDateTime.parse(s).atZone(ZONE).toInstant();
        } catch (DateTimeParseException e3) {
          throw new ValidationException("takenAt must be a date and time, such as 2026-11-27T08:30.");
        }
      }
    }
    if (at.isAfter(Instant.now().plusSeconds(300))) throw new ValidationException("takenAt is in the future.");
    if (at.isBefore(Instant.parse("1900-01-01T00:00:00Z"))) throw new ValidationException("takenAt is out of range.");
    return at;
  }

  /** Readings for the export and the tools: kind, value with unit, when. */
  public static Map<String, Object> brief(ObjectNode r) {
    var m = new java.util.LinkedHashMap<String, Object>();
    m.put("kind", r.path("kind").asText());
    m.put("value", r.path("value").decimalValue());
    if (r.hasNonNull("value2")) m.put("value2", r.get("value2").decimalValue());
    m.put("unit", r.path("unit").asText());
    m.put("takenAt", r.path("takenAt").asText());
    if (r.has("evaluation")) m.put("evaluation", r.get("evaluation"));
    return m;
  }
}
