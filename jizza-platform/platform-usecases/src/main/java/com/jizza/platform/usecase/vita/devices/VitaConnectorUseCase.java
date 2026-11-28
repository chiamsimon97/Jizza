package com.jizza.platform.usecase.vita.devices;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.error.ConflictException;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.finance.FinanceStore;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.vita.VitaRecords;
import com.jizza.platform.usecase.vita.measure.VitaMeasurementUseCase;
import com.jizza.platform.usecase.vita.records.VitaRecordsUseCase;
import com.jizza.platform.usecase.vita.sharing.VitaScopeResolver;
import com.jizza.platform.usecase.vita.sharing.VitaScopeResolver.PersonScope;
import com.jizza.platform.usecase.vita.sharing.VitaSection;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Health data from phones (spec 4.2 {@code vita_connector_grant}, 5.11, Phase 3). A grant says which
 * data types one phone (or, on the web, no phone) may send for one person from Health Connect or
 * Apple Health. A batch of records is saved without a confirm step, only for the types granted,
 * each with its source, its external id and the grant it came through; the same record sent again
 * adds nothing. Steps and sleep are daily totals, heart rate only resting values, workouts keep
 * their type. A kind a person can also type goes through the same entry format, evaluator and
 * duplicate check as a typed reading, and a red flag from a phone alerts without asking.
 *
 * <p>Revoking a grant stops its sync; the answer says how many rows it wrote, so the screen can
 * offer to delete them, and a second call with {@code deleteData} does. Nothing here logs a value.
 */
@Service
public class VitaConnectorUseCase {

  public static final String TABLE = "vita_connector_grant";
  static final String MEASUREMENTS = "vita_measurement";
  public static final Set<String> SOURCES = Set.of("health_connect", "apple_health");
  /** The data types a phone may send, named as the readings they become. */
  public static final List<String> DATA_TYPES = List.of("steps", "sleep", "workout", "resting_heart_rate", "bp",
      "glucose", "weight", "spo2", "temperature");
  /** A batch's size limit: a product choice that keeps one request small. */
  public static final int MAX_BATCH = 500;
  static final int MAX_EXTERNAL_ID = 200;
  static final int MAX_WORKOUT_TYPE = 40;
  /** A sleep or a workout longer than a day is not one session. */
  static final Duration LONGEST_SESSION = Duration.ofHours(24);
  private static final Pattern WHOLE = Pattern.compile("\\d{2,3}");

  private final VitaScopeResolver scopes;
  private final VitaRecords records;
  private final VitaMeasurementUseCase measurements;
  private final FinanceStore people;
  private final AuditLog audit;

  public VitaConnectorUseCase(VitaScopeResolver scopes, VitaRecords records, VitaMeasurementUseCase measurements,
                              FinanceStore people, AuditLog audit) {
    this.scopes = scopes;
    this.records = records;
    this.measurements = measurements;
    this.people = people;
    this.audit = audit;
  }

  /** The phone a call comes from, or none for the web. */
  public record Phone(UUID deviceId, String deviceName) {
    public static final Phone WEB = new Phone(null, null);
  }

  // ── Grants ──────────────────────────────────────────────────────────────────

  /**
   * Grants the caller can see: one person's when {@code personId} is given, otherwise those of every
   * person they manage or that is shared with them with View on vitals. A phone sees only its own.
   */
  public List<ObjectNode> list(UUID caller, UUID personId, Phone phone) {
    List<PersonScope> reach = new ArrayList<>();
    if (personId != null) {
      reach.add(scopes.person(caller, personId, VitaSection.VITALS, false));
    } else {
      for (var person : people.people(caller)) {
        if (!"huf".equals(person.kind())) reach.add(scopes.person(caller, person.id()));
      }
      for (PersonScope shared : scopes.sharedWithMe(caller)) {
        if (shared.canView(VitaSection.VITALS)) reach.add(shared);
      }
    }
    List<ObjectNode> out = new ArrayList<>();
    for (PersonScope p : reach) {
      for (ObjectNode g : grants(p)) {
        if (phone.deviceId() != null && !phone.deviceId().toString().equals(g.path("deviceId").asText(null))) continue;
        out.add(view(p, g));
      }
    }
    out.sort(Comparator.comparing((ObjectNode g) -> !g.path("revokedAt").isNull())
        .thenComparing(g -> g.path("grantedAt").asText(""), Comparator.reverseOrder()));
    return out;
  }

  /**
   * Turns on the data types sent in {@code dataTypes} for one person and source from this phone
   * (or the web), replacing the types of a live grant for the same person, source and phone.
   * Turning a type off here stops it being accepted; {@link #revoke} also offers to delete it.
   */
  @Transactional
  public ObjectNode grant(UUID caller, JsonNode body, Phone phone) {
    if (body == null || !body.isObject()) throw new ValidationException("Send the grant as an object.");
    for (var it = body.fieldNames(); it.hasNext(); ) {
      String f = it.next();
      if (!Set.of("personId", "source", "dataTypes").contains(f)) throw new ValidationException(f + " cannot be set here.");
    }
    UUID personId = uuid(body.get("personId"), "personId");
    PersonScope p = scopes.person(caller, personId, VitaSection.VITALS, true).requireOpen();
    String source = body.path("source").asText("");
    if (!SOURCES.contains(source)) throw new ValidationException("source must be health_connect or apple_health.");
    List<String> types = types(body.get("dataTypes"));
    if (types.isEmpty()) throw new ValidationException("Turn on at least one data type, or remove the connection.");
    Optional<ObjectNode> live = live(p, source, phone.deviceId());
    ObjectNode row;
    ArrayNode list = Json.mapper().createArrayNode();
    types.forEach(list::add);
    if (live.isPresent()) {
      UUID id = UUID.fromString(live.get().path("id").asText());
      ObjectNode change = Json.obj();
      change.set("dataTypes", list);
      if (phone.deviceName() != null) change.put("deviceName", clip(phone.deviceName(), 120));
      row = records.update(TABLE, p.owner(), id, change).orElseThrow(() -> new NotFoundException("Connection", id));
      audit.record(p.owner(), ActorType.USER, caller, "vita.connector.changed", id.toString(), null);
    } else {
      ObjectNode v = Json.obj().put("personId", p.personId().toString()).put("source", source)
          .put("createdByUserId", caller.toString());
      v.set("dataTypes", list);
      if (phone.deviceId() != null) v.put("deviceId", phone.deviceId().toString());
      if (phone.deviceName() != null) v.put("deviceName", clip(phone.deviceName(), 120));
      row = records.insert(TABLE, p.owner(), v);
      audit.record(p.owner(), ActorType.USER, caller, "vita.connector.granted", row.path("id").asText(), null);
    }
    return view(p, row);
  }

  /**
   * Stops a grant: with {@code types}, only those data types (the grant ends when none is left);
   * otherwise the whole grant. With {@code deleteData}, the readings it wrote for those types are
   * removed too; a revoked grant can be called again just to delete. The answer carries
   * {@code rowsWritten}: what is still there, for the screen to offer deleting.
   */
  @Transactional
  public ObjectNode revoke(UUID caller, UUID personId, UUID id, List<String> types, boolean deleteData, Phone phone) {
    PersonScope p = scopes.person(caller, personId, VitaSection.VITALS, true);
    ObjectNode g = grant(p, id, phone);
    if (!p.manager() && !caller.toString().equals(g.path("createdByUserId").asText(null))) {
      throw new NotFoundException("Connection", id);
    }
    List<String> current = typesOf(g);
    List<String> off = types == null || types.isEmpty() ? current : types(Json.toNode(types));
    if (g.path("revokedAt").isNull()) {
      List<String> left = new ArrayList<>(current);
      left.removeAll(off);
      ObjectNode change = Json.obj();
      if (left.isEmpty()) {
        change.put("revokedAt", Instant.now().toString());
      } else {
        ArrayNode list = Json.mapper().createArrayNode();
        left.forEach(list::add);
        change.set("dataTypes", list);
      }
      g = records.update(TABLE, p.owner(), id, change).orElseThrow(() -> new NotFoundException("Connection", id));
      audit.record(p.owner(), ActorType.USER, caller, left.isEmpty() ? "vita.connector.revoked" : "vita.connector.changed",
          id.toString(), null);
    }
    int deleted = 0;
    if (deleteData) {
      Instant now = Instant.now();
      for (ObjectNode r : written(p, id)) {
        if (!off.contains(r.path("kind").asText())) continue;
        records.update(MEASUREMENTS, p.owner(), UUID.fromString(r.path("id").asText()),
            Json.obj().put("deletedAt", now.toString()));
        deleted++;
      }
      audit.record(p.owner(), ActorType.USER, caller, "vita.connector.data_removed", id.toString(),
          Json.obj().put("rows", deleted));
    }
    ObjectNode out = view(p, g);
    out.put("rowsDeleted", deleted);
    return out;
  }

  // ── Ingest ──────────────────────────────────────────────────────────────────

  /** What happened to one record of a batch. */
  public record Outcome(String externalId, String status, String id, String reason, boolean alerted) {}

  /**
   * Saves a batch sent through one grant: {@code {"personId","grantId","records":[…]}}. Each record
   * answers on its own: added, updated (a total the phone revised), unchanged (sent before),
   * duplicate (the same reading was already typed), or refused with the reason. A revoked grant
   * refuses the whole batch with 409, a batch over {@link #MAX_BATCH} with 400.
   */
  public ObjectNode ingest(UUID caller, JsonNode body, Phone phone) {
    if (body == null || !body.isObject()) throw new ValidationException("Send the batch as an object.");
    UUID personId = uuid(body.get("personId"), "personId");
    UUID grantId = uuid(body.get("grantId"), "grantId");
    JsonNode list = body.get("records");
    if (list == null || !list.isArray()) throw new ValidationException("records must be a list.");
    if (list.size() > MAX_BATCH) throw new ValidationException("Send at most " + MAX_BATCH + " records in one batch.");
    PersonScope p = scopes.person(caller, personId, VitaSection.VITALS, true).requireOpen();
    ObjectNode g = grant(p, grantId, phone);
    if (!g.path("revokedAt").isNull()) {
      throw new ConflictException("This connection was removed, so nothing more is taken from it. Connect again to sync.");
    }
    String source = g.path("source").asText();
    Set<String> granted = new LinkedHashSet<>(typesOf(g));
    String device = g.hasNonNull("deviceName") ? g.get("deviceName").asText() : null;
    List<Outcome> outcomes = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    for (JsonNode r : list) {
      outcomes.add(one(p, r, source, grantId, granted, device, seen));
    }
    records.update(TABLE, p.owner(), grantId, Json.obj().put("lastSyncAt", Instant.now().toString()));
    Map<String, Integer> counts = new LinkedHashMap<>();
    for (String s : List.of("added", "updated", "unchanged", "duplicate", "refused")) counts.put(s, 0);
    outcomes.forEach(o -> counts.merge(o.status(), 1, Integer::sum));
    audit.record(p.owner(), ActorType.USER, caller, "vita.connector.synced", grantId.toString(), Json.toNode(counts));
    ObjectNode out = Json.obj().put("grantId", grantId.toString()).put("personId", p.personId().toString());
    counts.forEach(out::put);
    ArrayNode results = out.putArray("results");
    for (Outcome o : outcomes) {
      ObjectNode n = results.addObject().put("externalId", o.externalId()).put("status", o.status());
      if (o.id() != null) n.put("id", o.id());
      if (o.reason() != null) n.put("reason", o.reason());
      if (o.alerted()) n.put("alerted", true);
    }
    return out;
  }

  private Outcome one(PersonScope p, JsonNode r, String source, UUID grantId, Set<String> granted, String device,
                      Set<String> seen) {
    String ext = r != null && r.hasNonNull("externalId") ? r.get("externalId").asText().strip() : "";
    try {
      if (r == null || !r.isObject()) throw new ValidationException("Each record is an object.");
      if (ext.isEmpty()) throw new ValidationException("externalId is needed.");
      if (ext.length() > MAX_EXTERNAL_ID) throw new ValidationException("externalId is " + MAX_EXTERNAL_ID + " characters at most.");
      if (!seen.add(ext)) throw new ValidationException("The same externalId is twice in this batch.");
      String type = r.path("type").asText("");
      if (!DATA_TYPES.contains(type)) {
        throw new ValidationException("heart_rate".equals(type)
            ? "Only resting heart rate is kept: send resting_heart_rate."
            : "type must be one of " + String.join(", ", DATA_TYPES) + ".");
      }
      if (!granted.contains(type)) throw new ValidationException(type + " is not turned on for this connection.");
      ObjectNode reading = shape(type, r);
      ObjectNode extra = Json.obj().put("externalId", ext).put("sourceRef", grantId.toString());
      if (device != null) extra.put("device", device);
      Optional<ObjectNode> before = sent(p, source, ext);
      if (before.isPresent()) return again(p, before.get(), reading, ext);
      if (VitaMeasurementUseCase.KINDS.contains(type)) VitaMeasurementUseCase.check(reading);
      ObjectNode saved = measurements.addFromDevice(p, reading, extra, source);
      if (saved.hasNonNull("duplicateOf")) return new Outcome(ext, "duplicate", saved.get("duplicateOf").asText(), null, false);
      return new Outcome(ext, "added", saved.path("id").asText(), null, saved.path("alerted").asBoolean(false));
    } catch (ValidationException e) {
      return new Outcome(ext.isEmpty() ? null : ext, "refused", null, e.getMessage(), false);
    }
  }

  /** A record sent before: a revised total or session replaces the values; a removed row stays removed. */
  private Outcome again(PersonScope p, ObjectNode row, ObjectNode reading, String ext) {
    String id = row.path("id").asText();
    if (!row.path("deletedAt").isNull()) return new Outcome(ext, "unchanged", null, null, false);
    if (!row.path("kind").asText().equals(reading.path("kind").asText())) {
      throw new ValidationException("This externalId was sent before as another type.");
    }
    if (VitaMeasurementUseCase.KINDS.contains(reading.path("kind").asText())) {
      // A reading a person could type is kept as first saved: its evaluation and any alert stand.
      return new Outcome(ext, "unchanged", id, null, false);
    }
    ObjectNode change = Json.obj();
    for (String f : List.of("value", "value2", "value3")) {
      BigDecimal a = row.hasNonNull(f) ? row.get(f).decimalValue() : null;
      BigDecimal b = reading.hasNonNull(f) ? reading.get(f).decimalValue() : null;
      if (a == null ? b != null : b == null || a.compareTo(b) != 0) change.set(f, reading.get(f));
    }
    for (String f : List.of("takenAt", "startedAt", "endedAt")) {
      Instant a = instant(row.path(f).asText(null));
      Instant b = instant(reading.path(f).asText(null));
      if (!Objects.equals(a, b)) change.set(f, reading.get(f));
    }
    if (!Objects.equals(row.get("detail"), reading.get("detail")) && reading.has("detail")) change.set("detail", reading.get("detail"));
    if (change.isEmpty()) return new Outcome(ext, "unchanged", id, null, false);
    records.update(MEASUREMENTS, p.owner(), UUID.fromString(id), change);
    return new Outcome(ext, "updated", id, null, false);
  }

  /**
   * One record as the reading it becomes. Steps: {@code date} (yyyy-mm-dd, the day in {@code zone},
   * India by default) and the day's total. Sleep and workouts: {@code startedAt} and
   * {@code endedAt}; sleep's value is its hours, a workout's its minutes and {@code workoutType}.
   * Resting heart rate, BP, sugar, weight, SpO2 and temperature: the value(s) and {@code takenAt},
   * in mmHg, mg/dL, kg (or lb), % and °C (or °F), rounded to the precision a person would type.
   */
  static ObjectNode shape(String type, JsonNode r) {
    ObjectNode v = Json.obj().put("kind", type);
    switch (type) {
      case "steps" -> {
        LocalDate day = day(r.get("date"));
        ZoneId zone = zone(r.get("zone"));
        BigDecimal steps = number(r.get("value"), "value");
        if (steps.signum() < 0 || steps.stripTrailingZeros().scale() > 0) {
          throw new ValidationException("value must be the day's whole number of steps.");
        }
        Instant start = day.atStartOfDay(zone).toInstant();
        if (start.isAfter(Instant.now())) throw new ValidationException("date is in the future.");
        v.put("value", steps.setScale(0, RoundingMode.HALF_UP)).put("unit", "steps")
            .put("takenAt", start.toString()).put("startedAt", start.toString())
            .put("endedAt", day.plusDays(1).atStartOfDay(zone).toInstant().toString());
        v.set("detail", Json.obj().put("date", day.toString()).put("zone", zone.getId()));
      }
      case "sleep", "workout" -> {
        Instant a = when(r.get("startedAt"), "startedAt");
        Instant b = when(r.get("endedAt"), "endedAt");
        if (!b.isAfter(a)) throw new ValidationException("endedAt must be after startedAt.");
        if (Duration.between(a, b).compareTo(LONGEST_SESSION) > 0) throw new ValidationException("A session is 24 hours at most.");
        long minutes = Duration.between(a, b).toMinutes();
        v.put("startedAt", a.toString()).put("endedAt", b.toString()).put("takenAt", ("sleep".equals(type) ? b : a).toString());
        if ("sleep".equals(type)) {
          v.put("value", BigDecimal.valueOf(minutes).divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP)).put("unit", "h");
        } else {
          String kind = r.hasNonNull("workoutType") ? r.get("workoutType").asText().strip() : "";
          if (kind.isEmpty()) throw new ValidationException("workoutType is needed, such as walking.");
          if (kind.length() > MAX_WORKOUT_TYPE) throw new ValidationException("workoutType is " + MAX_WORKOUT_TYPE + " characters at most.");
          v.put("value", minutes).put("unit", "min");
          v.set("detail", Json.obj().put("workoutType", kind));
        }
      }
      case "resting_heart_rate" -> {
        BigDecimal bpm = number(r.get("value"), "value").setScale(0, RoundingMode.HALF_UP);
        if (!WHOLE.matcher(bpm.toPlainString()).matches()) throw new ValidationException("value must have two or three digits.");
        v.put("value", bpm).put("unit", "bpm").put("takenAt", when(r.get("takenAt"), "takenAt").toString());
      }
      default -> {
        // A kind a person types: the same fields, rounded as typed; the entry format checks the rest.
        int scale = switch (type) {
          case "weight" -> 2;
          case "glucose", "temperature" -> 1;
          default -> 0;
        };
        v.put("value", number(r.get("value"), "value").setScale(scale, RoundingMode.HALF_UP));
        if ("bp".equals(type)) {
          v.put("value2", number(r.get("value2"), "value2").setScale(0, RoundingMode.HALF_UP));
          if (r.hasNonNull("value3")) v.put("value3", number(r.get("value3"), "value3").setScale(0, RoundingMode.HALF_UP));
        }
        if (r.hasNonNull("unit")) v.put("unit", r.get("unit").asText());
        if ("glucose".equals(type)) {
          String c = r.path("context").asText("random");
          v.put("context", VitaMeasurementUseCase.CONTEXTS.contains(c) ? c : "random");
        }
        v.put("takenAt", when(r.get("takenAt"), "takenAt").toString());
      }
    }
    return v;
  }

  // ── Helpers ─────────────────────────────────────────────────────────────────

  private List<ObjectNode> grants(PersonScope p) {
    return records.list(TABLE, p.owner(), Map.of("personId", p.personId()));
  }

  private Optional<ObjectNode> live(PersonScope p, String source, UUID deviceId) {
    return grants(p).stream()
        .filter(g -> g.path("revokedAt").isNull() && source.equals(g.path("source").asText()))
        .filter(g -> Objects.equals(deviceId == null ? null : deviceId.toString(), g.path("deviceId").asText(null)))
        .findFirst();
  }

  /** The grant, if it is this person's and was made from this phone (or, on the web, from none). */
  private ObjectNode grant(PersonScope p, UUID id, Phone phone) {
    return records.get(TABLE, p.owner(), id)
        .filter(g -> p.personId().toString().equals(g.path("personId").asText()))
        .filter(g -> Objects.equals(phone.deviceId() == null ? null : phone.deviceId().toString(), g.path("deviceId").asText(null)))
        .orElseThrow(() -> new NotFoundException("Connection", id));
  }

  private Optional<ObjectNode> sent(PersonScope p, String source, String ext) {
    Map<String, Object> where = new LinkedHashMap<>();
    where.put("personId", p.personId());
    where.put("source", source);
    where.put("externalId", ext);
    return records.list(MEASUREMENTS, p.owner(), where).stream().findFirst();
  }

  private List<ObjectNode> written(PersonScope p, UUID grantId) {
    Map<String, Object> where = new LinkedHashMap<>(VitaRecordsUseCase.livePerson(p.personId()));
    where.put("sourceRef", grantId.toString());
    return records.list(MEASUREMENTS, p.owner(), where);
  }

  private ObjectNode view(PersonScope p, ObjectNode g) {
    ObjectNode v = g.deepCopy();
    v.remove("userId");
    v.put("shared", p.shared());
    Map<String, Integer> byType = new LinkedHashMap<>();
    int total = 0;
    for (ObjectNode r : written(p, UUID.fromString(g.path("id").asText()))) {
      byType.merge(r.path("kind").asText(), 1, Integer::sum);
      total++;
    }
    v.put("rowsWritten", total);
    v.set("rowsByType", Json.toNode(byType));
    return v;
  }

  private static List<String> typesOf(ObjectNode g) {
    List<String> out = new ArrayList<>();
    g.path("dataTypes").forEach(n -> out.add(n.asText()));
    return out;
  }

  private static List<String> types(JsonNode v) {
    if (v == null || !v.isArray()) throw new ValidationException("dataTypes must be a list.");
    Set<String> out = new LinkedHashSet<>();
    for (JsonNode n : v) {
      String t = n.asText("");
      if (!DATA_TYPES.contains(t)) {
        throw new ValidationException("heart_rate".equals(t)
            ? "Only resting heart rate is kept: use resting_heart_rate."
            : "dataTypes are from " + String.join(", ", DATA_TYPES) + ".");
      }
      out.add(t);
    }
    return new ArrayList<>(out);
  }

  private static UUID uuid(JsonNode v, String name) {
    if (v == null || v.isNull()) throw new ValidationException(name + " is needed.");
    try {
      return UUID.fromString(v.asText());
    } catch (IllegalArgumentException e) {
      throw new ValidationException(name + " is not a valid id.");
    }
  }

  private static BigDecimal number(JsonNode v, String name) {
    if (v == null || v.isNull()) throw new ValidationException(name + " is needed.");
    if (!v.isNumber()) throw new ValidationException(name + " must be a number.");
    return v.decimalValue();
  }

  private static Instant when(JsonNode v, String name) {
    if (v == null || v.isNull()) throw new ValidationException(name + " is needed.");
    Instant at;
    try {
      at = java.time.OffsetDateTime.parse(v.asText()).toInstant();
    } catch (DateTimeParseException e) {
      try {
        at = Instant.parse(v.asText());
      } catch (DateTimeParseException e2) {
        throw new ValidationException(name + " must be a time with its offset, such as 2026-11-27T08:30:00Z.");
      }
    }
    if (at.isAfter(Instant.now().plusSeconds(300))) throw new ValidationException(name + " is in the future.");
    if (at.isBefore(Instant.parse("1900-01-01T00:00:00Z"))) throw new ValidationException(name + " is out of range.");
    return at;
  }

  private static LocalDate day(JsonNode v) {
    if (v == null || v.isNull()) throw new ValidationException("date is needed for steps.");
    try {
      return LocalDate.parse(v.asText());
    } catch (DateTimeParseException e) {
      throw new ValidationException("date must be yyyy-mm-dd.");
    }
  }

  private static ZoneId zone(JsonNode v) {
    if (v == null || v.isNull() || v.asText().isBlank()) return VitaRecordsUseCase.ZONE;
    try {
      return ZoneId.of(v.asText());
    } catch (DateTimeException e) {
      throw new ValidationException("zone must be a time zone such as Asia/Kolkata.");
    }
  }

  private static Instant instant(String s) {
    if (s == null) return null;
    try {
      return java.time.OffsetDateTime.parse(s).toInstant();
    } catch (DateTimeParseException e) {
      try {
        return Instant.parse(s);
      } catch (DateTimeParseException e2) {
        return null;
      }
    }
  }

  private static String clip(String s, int max) {
    return s.length() <= max ? s : s.substring(0, max);
  }
}
