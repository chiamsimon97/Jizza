package com.jizza.platform.usecase.vita.measure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.vita.VitaRecords;
import com.jizza.platform.usecase.vita.records.VitaRecordsUseCase;
import com.jizza.platform.usecase.vita.sharing.VitaScopeResolver;
import com.jizza.platform.usecase.vita.sharing.VitaScopeResolver.PersonScope;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
 * Readings (spec 4.2 {@code vita_measurement}, 5.12). Phase 0 takes weight only; blood pressure,
 * sugar and the rest arrive in Phase 1a with the target evaluator. Values are stored in canonical
 * units (weight in kg). A typed value must pass its kind's entry format before it is saved, so a
 * slip is refused; no measuring-range limit is set (spec 5.1: {@code clinical.entry_limits} is not
 * set today).
 */
@Service
public class VitaMeasurementUseCase {

  static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
  /** The kinds this phase accepts. */
  public static final Set<String> KINDS = Set.of("weight");
  /** A pound by definition, in kilograms: a unit conversion, not a clinical figure. */
  static final BigDecimal KG_PER_LB = new BigDecimal("0.45359237");
  /** Weight as typed: one to three digits, with up to two decimals. */
  private static final Pattern WEIGHT = Pattern.compile("\\d{1,3}(\\.\\d{1,2})?");
  private static final Set<String> FIELDS = Set.of("kind", "value", "unit", "takenAt", "note");
  private static final int MAX_NOTE = 500;

  private final VitaRecords records;
  private final VitaScopeResolver scopes;
  private final VitaRecordsUseCase forms;
  private final AuditLog audit;

  public VitaMeasurementUseCase(VitaRecords records, VitaScopeResolver scopes,
                                VitaRecordsUseCase forms, AuditLog audit) {
    this.records = records;
    this.scopes = scopes;
    this.forms = forms;
    this.audit = audit;
  }

  /** The person's live readings, newest first; {@code kind} narrows the list. */
  public List<ObjectNode> list(UUID caller, UUID personId, String kind) {
    PersonScope p = scopes.person(caller, personId);
    return list(p, kind);
  }

  public List<ObjectNode> list(PersonScope p, String kind) {
    var where = VitaRecordsUseCase.livePerson(p.personId());
    if (kind != null && !kind.isBlank()) {
      if (!KINDS.contains(kind)) throw new ValidationException("kind must be one of " + String.join(", ", KINDS) + ".");
      where.put("kind", kind);
    }
    return records.list("vita_measurement", p.owner(), where).stream()
        .sorted(Comparator.comparing((ObjectNode r) -> r.path("takenAt").asText("")).reversed()).toList();
  }

  public Optional<ObjectNode> latest(PersonScope p, String kind) {
    return list(p, kind).stream().findFirst();
  }

  @Transactional
  public ObjectNode create(UUID caller, UUID personId, JsonNode body) {
    return create(scopes.person(caller, personId), body, "manual");
  }

  @Transactional
  public ObjectNode create(PersonScope p, JsonNode body, String source) {
    p.requireOpen();
    ObjectNode values = clean(body, true);
    values.put("personId", p.personId().toString());
    values.put("source", VitaRecordsUseCase.SOURCES.contains(source) ? source : "manual");
    values.put("createdByUserId", p.caller().toString());
    if (!values.hasNonNull("takenAt")) values.put("takenAt", Instant.now().toString());
    ObjectNode row = records.insert("vita_measurement", p.owner(), values);
    audit.record(p.owner(), ActorType.USER, p.caller(), "vita.measurement.added", row.path("id").asText(), null);
    return row;
  }

  /** Corrects a reading: the fields sent replace what was there. */
  @Transactional
  public ObjectNode update(UUID caller, UUID personId, UUID id, JsonNode body) {
    PersonScope p = scopes.person(caller, personId).requireOpen();
    ObjectNode current = get(p, id);
    ObjectNode merged = body != null && body.isObject() ? ((ObjectNode) body).deepCopy() : null;
    if (merged == null) throw new ValidationException("Send the fields as an object.");
    if (merged.has("value") && !merged.has("unit")) merged.put("unit", current.path("unit").asText("kg"));
    if (!merged.has("kind")) merged.put("kind", current.path("kind").asText());
    ObjectNode values = clean(merged, false);
    values.remove("kind");
    ObjectNode row = records.update("vita_measurement", p.owner(), id, values)
        .orElseThrow(() -> new NotFoundException("Reading", id));
    audit.record(p.owner(), ActorType.USER, caller, "vita.measurement.updated", id.toString(), null);
    return row;
  }

  @Transactional
  public void delete(UUID caller, UUID personId, UUID id) {
    PersonScope p = scopes.person(caller, personId).requireOpen();
    forms.removeRow(p, "vita_measurement", "Reading", id);
    audit.record(p.owner(), ActorType.USER, caller, "vita.measurement.removed", id.toString(), null);
  }

  /** Moves a reading entered for the wrong person to another person of the same household. */
  @Transactional
  public ObjectNode move(UUID caller, UUID personId, UUID id, UUID toPersonId) {
    PersonScope from = scopes.person(caller, personId).requireOpen();
    get(from, id);
    PersonScope to = scopes.person(caller, toPersonId).requireOpen();
    ObjectNode row = records.update("vita_measurement", from.owner(), id,
            JsonNodeFactory.instance.objectNode().put("personId", to.personId().toString()))
        .orElseThrow(() -> new NotFoundException("Reading", id));
    audit.record(from.owner(), ActorType.USER, caller, "vita.measurement.moved", id.toString(), null);
    return row;
  }

  ObjectNode get(PersonScope p, UUID id) {
    return records.get("vita_measurement", p.owner(), id)
        .filter(r -> p.personId().toString().equals(r.path("personId").asText()))
        .filter(r -> r.path("deletedAt").isNull())
        .orElseThrow(() -> new NotFoundException("Reading", id));
  }

  /** Checks a reading's fields; weight is stored in kg. */
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
      throw new ValidationException("kind must be weight for now; other readings come with the next update.");
    }
    out.put("kind", kind);
    if (body.has("value") || creating) {
      String unit = body.path("unit").asText("kg");
      if (!Set.of("kg", "lb").contains(unit)) throw new ValidationException("unit must be kg or lb.");
      BigDecimal typed = weight(body.get("value"));
      BigDecimal kg = "lb".equals(unit) ? typed.multiply(KG_PER_LB).setScale(3, RoundingMode.HALF_UP) : typed;
      out.put("value", kg);
      out.put("unit", "kg");
      if ("lb".equals(unit)) out.set("detail", n.objectNode().put("typed", typed).put("typedUnit", "lb"));
      else out.putNull("detail");
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
    return Map.of("kind", r.path("kind").asText(), "value", r.path("value").decimalValue(),
        "unit", r.path("unit").asText(), "takenAt", r.path("takenAt").asText());
  }
}
