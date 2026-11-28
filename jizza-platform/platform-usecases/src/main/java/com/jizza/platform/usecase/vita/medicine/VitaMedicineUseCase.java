package com.jizza.platform.usecase.vita.medicine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.scheduler.RecurrenceDays;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.vita.VitaDoseStore;
import com.jizza.engine.api.vita.VitaDoseStore.NewDose;
import com.jizza.engine.api.vita.VitaRecords;
import com.jizza.engine.api.vita.VitaStore;
import com.jizza.platform.usecase.vita.config.VitaConfigService;
import com.jizza.platform.usecase.vita.records.VitaRecordsUseCase;
import com.jizza.platform.usecase.vita.records.VitaTable;
import com.jizza.platform.usecase.vita.sharing.VitaScopeResolver;
import com.jizza.platform.usecase.vita.sharing.VitaScopeResolver.PersonScope;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A person's medicines and every change to them (spec 4.2, 5.2, 5.12): add, correct, change the
 * dose or times from a date, pause, resume, stop and refill, with the history kept as events. Vita
 * keeps the list and reminds: it never changes a dose itself and never suggests starting or
 * stopping one. A name is kept exactly as the user wrote it. Also makes the dose rows (48 hours
 * ahead), lowers the stock when a dose is taken and sends the one refill reminder.
 */
@Service
public class VitaMedicineUseCase {

  private static final Pattern TIME = Pattern.compile("([01]\\d|2[0-3]):[0-5]\\d");
  private static final Set<String> UNITS = Set.of("tablet", "ml", "units", "puffs", "drops", "other");
  private static final Set<String> FOOD = Set.of("before", "with", "after", "any");
  private static final String MED = "vita_medicine";
  private static final String SCHED = "vita_medicine_schedule";
  private static final String EVENT = "vita_medicine_event";

  private final VitaRecords records;
  private final VitaDoseStore doses;
  private final VitaScopeResolver scopes;
  private final VitaRecordsUseCase forms;
  private final VitaStore store;
  private final VitaConfigService config;
  private final RecurrenceDays days;
  private final VitaReminderNotifier notifier;
  private final AuditLog audit;

  public VitaMedicineUseCase(VitaRecords records, VitaDoseStore doses, VitaScopeResolver scopes,
                             VitaRecordsUseCase forms, VitaStore store, VitaConfigService config,
                             RecurrenceDays days, VitaReminderNotifier notifier, AuditLog audit) {
    this.records = records;
    this.doses = doses;
    this.scopes = scopes;
    this.forms = forms;
    this.store = store;
    this.config = config;
    this.days = days;
    this.notifier = notifier;
    this.audit = audit;
  }

  // ── The person's zone and reminder mode ─────────────────────────────────────

  /** The person's zone (India when unset) and reminder mode ({@code each_slot} when unset). */
  public record PersonSettings(ZoneId zone, String mode) {}

  public PersonSettings settings(PersonScope p) {
    Optional<ObjectNode> profile = records.list("vita_profile", p.owner(), Map.of("personId", p.personId()))
        .stream().findFirst();
    ZoneId zone = VitaRecordsUseCase.ZONE;
    String mode = "each_slot";
    if (profile.isPresent()) {
      try {
        zone = ZoneId.of(profile.get().path("timezone").asText("Asia/Kolkata"));
      } catch (RuntimeException ignored) {
        // keep India
      }
      mode = profile.get().path("reminderMode").asText("each_slot");
    }
    return new PersonSettings(zone, mode);
  }

  public ReminderSettings reminderSettings(UUID owner) {
    return ReminderSettings.of(config, owner);
  }

  // ── Reading ─────────────────────────────────────────────────────────────────

  public List<ObjectNode> list(UUID caller, UUID personId) {
    PersonScope p = read(caller, personId);
    return medicines(p).stream().map(m -> view(p, m)).toList();
  }

  /** The person for reading medicines: own, or shared with the medicines section. */
  public PersonScope read(UUID caller, UUID personId) {
    return scopes.person(caller, personId, com.jizza.platform.usecase.vita.sharing.VitaSection.MEDICINES, false);
  }

  /** The person for adding or marking: own, or shared with Edit on medicines. */
  public PersonScope write(UUID caller, UUID personId) {
    return scopes.person(caller, personId, com.jizza.platform.usecase.vita.sharing.VitaSection.MEDICINES, true).requireOpen();
  }

  /** A change to a medicine: the manager's, or a helper's to a medicine they added themself. */
  private PersonScope ownEntry(UUID caller, UUID personId, UUID id) {
    PersonScope p = write(caller, personId);
    VitaRecordsUseCase.requireOwnEntry(p, medicine(p, id), "Medicine", id);
    return p;
  }

  public ObjectNode get(UUID caller, UUID personId, UUID id) {
    PersonScope p = read(caller, personId);
    return view(p, medicine(p, id));
  }

  /** The person's live medicines, oldest first. */
  public List<ObjectNode> medicines(PersonScope p) {
    return records.list(MED, p.owner(), VitaRecordsUseCase.livePerson(p.personId())).stream()
        .filter(m -> !VitaRecordsUseCase.hidden(p, m)).toList();
  }

  public ObjectNode medicine(PersonScope p, UUID id) {
    return records.get(MED, p.owner(), id)
        .filter(r -> p.personId().toString().equals(r.path("personId").asText()))
        .filter(r -> r.path("deletedAt").isNull())
        .filter(r -> !VitaRecordsUseCase.hidden(p, r))
        .orElseThrow(() -> new NotFoundException("Medicine", id));
  }

  /** The medicine's live schedules, oldest first. */
  public List<ObjectNode> schedules(PersonScope p, UUID medicineId) {
    Map<String, Object> where = new LinkedHashMap<>();
    where.put("medicineId", medicineId);
    where.put("deletedAt", null);
    return records.list(SCHED, p.owner(), where).stream()
        .sorted(Comparator.comparing((ObjectNode s) -> s.path("effectiveFrom").asText())).toList();
  }

  /** The schedule with no end, if any. */
  public Optional<ObjectNode> openSchedule(PersonScope p, UUID medicineId) {
    return schedules(p, medicineId).stream().filter(s -> s.path("effectiveTo").isNull()).reduce((a, b) -> b);
  }

  /** A medicine as the screens show it: the row with its current schedule. */
  public ObjectNode view(PersonScope p, ObjectNode m) {
    ObjectNode out = m.deepCopy();
    UUID id = UUID.fromString(m.path("id").asText());
    List<ObjectNode> all = schedules(p, id);
    Optional<ObjectNode> current = all.stream().filter(s -> s.path("effectiveTo").isNull()).reduce((a, b) -> b)
        .or(() -> all.stream().reduce((a, b) -> b));
    current.ifPresentOrElse(s -> out.set("schedule", s), () -> out.putNull("schedule"));
    out.put("refillDue", m.hasNonNull("refillRemindedAt"));
    return out;
  }

  public List<ObjectNode> events(UUID caller, UUID personId, UUID id) {
    PersonScope p = read(caller, personId);
    medicine(p, id);
    Map<String, Object> where = new LinkedHashMap<>();
    where.put("medicineId", id);
    where.put("deletedAt", null);
    return records.list(EVENT, p.owner(), where).stream()
        .sorted(Comparator.comparing((ObjectNode e) -> e.path("onDate").asText())
            .thenComparing(e -> e.path("createdAt").asText())).toList();
  }

  // ── Adding and correcting ──────────────────────────────────────────────────

  /**
   * Adds a medicine with its schedule ({@code schedule}: times, recurrence, doseUnits, doseUnit,
   * withFood). An as-needed medicine and a supply have no schedule.
   */
  @Transactional
  public ObjectNode create(UUID caller, UUID personId, JsonNode body, String source) {
    return create(write(caller, personId), body, source, Instant.now());
  }

  @Transactional
  public ObjectNode create(PersonScope p, JsonNode body, String source, Instant now) {
    p.requireOpen();
    if (body == null || !body.isObject()) throw new ValidationException("Send the medicine as an object.");
    ObjectNode b = ((ObjectNode) body).deepCopy();
    JsonNode scheduleBody = b.remove("schedule");
    if (medicines(p).size() >= VitaTable.MEDICINE.maxPerPerson) {
      throw new ValidationException("A record can hold at most " + VitaTable.MEDICINE.maxPerPerson + " medicines.");
    }
    ObjectNode values = VitaRecordsUseCase.clean(VitaTable.MEDICINE.fields, b, true);
    forms.checkRefs(p.owner(), VitaTable.MEDICINE.fields, values);
    PersonSettings ps = settings(p);
    LocalDate today = now.atZone(ps.zone()).toLocalDate();
    if (!values.hasNonNull("startDate")) values.put("startDate", today.toString());
    checkDates(values);
    boolean noDoses = values.path("asNeeded").asBoolean(false) || "supply".equals(values.path("form").asText());
    if (noDoses && scheduleBody != null && !scheduleBody.isNull()) {
      throw new ValidationException("An as-needed medicine or a supply has no schedule.");
    }
    if (!noDoses && (scheduleBody == null || scheduleBody.isNull())) {
      throw new ValidationException("Give the times it is taken (schedule.times), or mark it as needed.");
    }
    if (values.path("trackStock").asBoolean(false) && !values.hasNonNull("stockUnits")) {
      throw new ValidationException("stockUnits is needed to track stock.");
    }
    ObjectNode schedule = noDoses ? null : schedule(scheduleBody, null);
    store.ensureProfile(p.owner(), p.personId(), p.caller());
    values.put("personId", p.personId().toString());
    values.put("source", VitaRecordsUseCase.SOURCES.contains(source) ? source : "manual");
    values.put("createdByUserId", p.caller().toString());
    values.put("status", "active");
    ObjectNode med = records.insert(MED, p.owner(), values);
    UUID id = UUID.fromString(med.path("id").asText());
    if (schedule != null) insertSchedule(p, id, schedule, LocalDate.parse(med.path("startDate").asText()));
    event(p, id, "started", LocalDate.parse(med.path("startDate").asText()), null,
        describe(med.path("strength").asText(null), schedule), null, med.path("prescribedBy").asText(null), null, source);
    audit.record(p.owner(), ActorType.USER, p.caller(), "vita.medicine.added", id.toString(), null);
    expand(p, now);
    refillCheck(p, records.get(MED, p.owner(), id).orElseThrow(), reminderSettings(p.owner()), now);
    return view(p, medicine(p, id));
  }

  /**
   * Corrects the medicine's details (name, notes, stock and the like). The dose and times change
   * only through {@link #change}; the status only through pause, resume and stop.
   */
  @Transactional
  public ObjectNode update(UUID caller, UUID personId, UUID id, JsonNode body) {
    PersonScope p = ownEntry(caller, personId, id);
    ObjectNode current = medicine(p, id);
    if (body != null && body.has("schedule")) {
      throw new ValidationException("Use Change to change the dose or the times.");
    }
    ObjectNode values = VitaRecordsUseCase.clean(VitaTable.MEDICINE.fields, body, false);
    forms.checkRefs(p.owner(), VitaTable.MEDICINE.fields, values);
    ObjectNode merged = current.deepCopy();
    merged.setAll(values);
    checkDates(merged);
    if (merged.path("trackStock").asBoolean(false) && !merged.hasNonNull("stockUnits")) {
      throw new ValidationException("stockUnits is needed to track stock.");
    }
    if (values.has("stockUnits") || values.has("refillLeadDays") || values.has("trackStock")) values.putNull("refillRemindedAt");
    ObjectNode row = values.isEmpty() ? current
        : records.update(MED, p.owner(), id, values).orElseThrow(() -> new NotFoundException("Medicine", id));
    if (values.has("endDate") || values.has("startDate")) {
      // A shorter course removes the rows past its new end; a longer one makes the new rows.
      doses.removeFutureOfMedicine(p.owner(), id, Instant.now());
      expand(p, Instant.now());
    }
    refillCheck(p, row, reminderSettings(p.owner()), Instant.now());
    audit.record(p.owner(), ActorType.USER, caller, "vita.medicine.updated", id.toString(), null);
    return view(p, medicine(p, id));
  }

  // ── Changes, each kept as an event ──────────────────────────────────────────

  /**
   * A change the doctor made, from a date (spec 5.2, Fixture G): {@code fromDate} (default today),
   * {@code strength}, {@code schedule} (any of times, recurrence, doseUnits, doseUnit, withFood),
   * {@code doctorId}, {@code visitId}, {@code reason}. The open schedule closes the day before, its
   * un-acted rows from that date go, its past rows stay, and a new schedule starts on the date.
   */
  @Transactional
  public ObjectNode change(UUID caller, UUID personId, UUID id, JsonNode body) {
    return change(ownEntry(caller, personId, id), id, body, Instant.now());
  }

  @Transactional
  public ObjectNode change(PersonScope p, UUID id, JsonNode body, Instant now) {
    p.requireOpen();
    ObjectNode med = medicine(p, id);
    if ("stopped".equals(med.path("status").asText())) throw new ValidationException("This medicine is stopped.");
    if (body == null || !body.isObject()) throw new ValidationException("Send the change as an object.");
    PersonSettings ps = settings(p);
    LocalDate today = now.atZone(ps.zone()).toLocalDate();
    LocalDate from = body.hasNonNull("fromDate") ? VitaRecordsUseCase.anyDate("fromDate", body.get("fromDate")) : today;
    if (from.isBefore(LocalDate.parse(med.path("startDate").asText()))) {
      throw new ValidationException("fromDate is before the medicine started.");
    }
    UUID doctor = ref(p, body, "doctorId", "vita_doctor", "Doctor");
    UUID visit = ref(p, body, "visitId", "vita_visit", "Visit");
    String reason = text(body, "reason", 500);
    String oldStrength = med.path("strength").asText(null);
    String newStrength = body.has("strength") ? text(body, "strength", 40) : oldStrength;
    Optional<ObjectNode> open = openSchedule(p, id);
    ObjectNode oldSchedule = open.orElse(null);
    ObjectNode newSchedule = oldSchedule;
    if (body.hasNonNull("schedule")) {
      if (oldSchedule == null) throw new ValidationException("This medicine has no schedule to change.");
      newSchedule = schedule(body.get("schedule"), oldSchedule);
    }
    boolean strengthChanged = !java.util.Objects.equals(oldStrength, newStrength);
    boolean scheduleChanged = oldSchedule != null && !sameSchedule(oldSchedule, newSchedule);
    if (!strengthChanged && !scheduleChanged) throw new ValidationException("Nothing to change.");

    if (scheduleChanged || strengthChanged) {
      if (oldSchedule != null) {
        UUID oldId = UUID.fromString(oldSchedule.path("id").asText());
        LocalDate oldFrom = LocalDate.parse(oldSchedule.path("effectiveFrom").asText());
        LocalDate to = from.minusDays(1).isBefore(oldFrom.minusDays(1)) ? oldFrom.minusDays(1) : from.minusDays(1);
        records.update(SCHED, p.owner(), oldId, Json.obj().put("effectiveTo", to.toString()));
        doses.removeFutureOfSchedule(p.owner(), oldId, from.atStartOfDay(ps.zone()).toInstant());
        insertSchedule(p, id, newSchedule, from);
      }
    }
    if (strengthChanged) records.update(MED, p.owner(), id, Json.obj().put("strength", newStrength));
    String fromText;
    String toText;
    if (strengthChanged && !scheduleChanged) {
      fromText = oldStrength;
      toText = newStrength;
    } else if (!strengthChanged) {
      fromText = describe(null, oldSchedule);
      toText = describe(null, newSchedule);
    } else {
      fromText = describe(oldStrength, oldSchedule);
      toText = describe(newStrength, newSchedule);
    }
    event(p, id, "dose_changed", from, fromText, toText, reason, doctor == null ? null : doctor.toString(),
        visit == null ? null : visit.toString(), "manual");
    audit.record(p.owner(), ActorType.USER, p.caller(), "vita.medicine.changed", id.toString(), null);
    expand(p, now);
    return view(p, medicine(p, id));
  }

  /** Pauses: no rows are made while paused, and the future un-acted ones go. */
  @Transactional
  public ObjectNode pause(UUID caller, UUID personId, UUID id, JsonNode body) {
    PersonScope p = ownEntry(caller, personId, id);
    return pause(p, id, body, Instant.now());
  }

  @Transactional
  public ObjectNode pause(PersonScope p, UUID id, JsonNode body, Instant now) {
    ObjectNode med = medicine(p, id);
    if (!"active".equals(med.path("status").asText())) throw new ValidationException("Only an active medicine can be paused.");
    records.update(MED, p.owner(), id, Json.obj().put("status", "paused"));
    doses.removeFutureOfMedicine(p.owner(), id, now);
    event(p, id, "paused", now.atZone(settings(p).zone()).toLocalDate(), null, null, text(body, "reason", 500),
        null, null, "manual");
    audit.record(p.owner(), ActorType.USER, p.caller(), "vita.medicine.paused", id.toString(), null);
    return view(p, medicine(p, id));
  }

  @Transactional
  public ObjectNode resume(UUID caller, UUID personId, UUID id, JsonNode body) {
    PersonScope p = ownEntry(caller, personId, id);
    return resume(p, id, body, Instant.now());
  }

  @Transactional
  public ObjectNode resume(PersonScope p, UUID id, JsonNode body, Instant now) {
    ObjectNode med = medicine(p, id);
    if (!"paused".equals(med.path("status").asText())) throw new ValidationException("Only a paused medicine can be resumed.");
    records.update(MED, p.owner(), id, Json.obj().put("status", "active"));
    event(p, id, "resumed", now.atZone(settings(p).zone()).toLocalDate(), null, null, text(body, "reason", 500),
        null, null, "manual");
    audit.record(p.owner(), ActorType.USER, p.caller(), "vita.medicine.resumed", id.toString(), null);
    expand(p, now);
    return view(p, medicine(p, id));
  }

  /** Stops: the course ends today, the schedule closes and the future un-acted rows go. */
  @Transactional
  public ObjectNode stop(UUID caller, UUID personId, UUID id, JsonNode body) {
    PersonScope p = ownEntry(caller, personId, id);
    return stop(p, id, body, Instant.now());
  }

  @Transactional
  public ObjectNode stop(PersonScope p, UUID id, JsonNode body, Instant now) {
    ObjectNode med = medicine(p, id);
    if ("stopped".equals(med.path("status").asText())) throw new ValidationException("This medicine is already stopped.");
    LocalDate today = now.atZone(settings(p).zone()).toLocalDate();
    stopOn(p, med, today, text(body, "reason", 500), now);
    audit.record(p.owner(), ActorType.USER, p.caller(), "vita.medicine.stopped", id.toString(), null);
    return view(p, medicine(p, id));
  }

  private void stopOn(PersonScope p, ObjectNode med, LocalDate day, String reason, Instant now) {
    UUID id = UUID.fromString(med.path("id").asText());
    LocalDate start = LocalDate.parse(med.path("startDate").asText());
    ObjectNode values = Json.obj().put("status", "stopped");
    LocalDate end = med.hasNonNull("endDate") ? LocalDate.parse(med.path("endDate").asText()) : null;
    if (end == null || end.isAfter(day)) values.put("endDate", (day.isBefore(start) ? start : day).toString());
    records.update(MED, p.owner(), id, values);
    openSchedule(p, id).ifPresent(s -> {
      LocalDate from = LocalDate.parse(s.path("effectiveFrom").asText());
      LocalDate to = day.isBefore(from.minusDays(1)) ? from.minusDays(1) : day;
      records.update(SCHED, p.owner(), UUID.fromString(s.path("id").asText()), Json.obj().put("effectiveTo", to.toString()));
    });
    doses.removeFutureOfMedicine(p.owner(), id, now);
    event(p, id, "stopped", day, null, null, reason, null, null, "manual");
  }

  /**
   * A refill (Fixture C1b): adds {@code units} (default one pack), writes a {@code refilled}
   * event and clears the refill reminder.
   */
  @Transactional
  public ObjectNode refill(UUID caller, UUID personId, UUID id, JsonNode body) {
    PersonScope p = write(caller, personId);
    return refill(p, id, body, Instant.now());
  }

  @Transactional
  public ObjectNode refill(PersonScope p, UUID id, JsonNode body, Instant now) {
    ObjectNode med = medicine(p, id);
    if (!med.path("trackStock").asBoolean(false)) throw new ValidationException("Turn on stock tracking first.");
    BigDecimal units;
    JsonNode u = body == null ? null : body.get("units");
    if (u != null && !u.isNull()) {
      if (!u.isNumber() || u.decimalValue().signum() <= 0 || u.decimalValue().compareTo(BigDecimal.valueOf(10000)) > 0) {
        throw new ValidationException("units must be a number above 0.");
      }
      units = u.decimalValue();
    } else if (med.hasNonNull("unitsPerPack")) {
      units = med.get("unitsPerPack").decimalValue();
    } else {
      throw new ValidationException("units is needed (or set the pack size).");
    }
    BigDecimal before = med.hasNonNull("stockUnits") ? med.get("stockUnits").decimalValue() : BigDecimal.ZERO;
    BigDecimal after = StockTracker.afterRefill(before, units);
    records.update(MED, p.owner(), id, Json.obj().put("stockUnits", after).putNull("refillRemindedAt"));
    Map<String, Object> where = new LinkedHashMap<>();
    where.put("personId", p.personId());
    where.put("kind", "refill");
    where.put("refId", id);
    where.put("deletedAt", null);
    for (ObjectNode r : records.list("vita_reminder", p.owner(), where)) {
      if (Set.of("scheduled", "sent").contains(r.path("status").asText())) {
        records.update("vita_reminder", p.owner(), UUID.fromString(r.path("id").asText()), Json.obj().put("status", "done"));
      }
    }
    event(p, id, "refilled", now.atZone(settings(p).zone()).toLocalDate(), plain(before), plain(after),
        text(body, "reason", 500), null, null, "manual");
    audit.record(p.owner(), ActorType.USER, p.caller(), "vita.medicine.refilled", id.toString(), null);
    refillCheck(p, records.get(MED, p.owner(), id).orElseThrow(), reminderSettings(p.owner()), now);
    return view(p, medicine(p, id));
  }

  // ── Dose rows, stock and the refill reminder ───────────────────────────────

  /** One open schedule of an active medicine as the expander reads it, clipped to the course. */
  public Optional<DoseExpander.Plan> plan(ObjectNode med, ObjectNode s) {
    if (!"active".equals(med.path("status").asText()) || med.path("asNeeded").asBoolean(false)
        || "supply".equals(med.path("form").asText()) || !med.path("deletedAt").isNull() || !s.path("deletedAt").isNull()) {
      return Optional.empty();
    }
    LocalDate from = LocalDate.parse(s.path("effectiveFrom").asText());
    LocalDate start = LocalDate.parse(med.path("startDate").asText());
    if (start.isAfter(from)) from = start;
    LocalDate to = s.hasNonNull("effectiveTo") ? LocalDate.parse(s.path("effectiveTo").asText()) : null;
    if (med.hasNonNull("endDate")) {
      LocalDate end = LocalDate.parse(med.path("endDate").asText());
      if (to == null || end.isBefore(to)) to = end;
    }
    if (to != null && to.isBefore(from)) return Optional.empty();
    List<LocalTime> times = new ArrayList<>();
    s.path("times").forEach(t -> times.add(LocalTime.parse(t.asText())));
    return Optional.of(new DoseExpander.Plan(UUID.fromString(s.path("id").asText()),
        UUID.fromString(med.path("id").asText()), UUID.fromString(med.path("personId").asText()),
        times, s.path("recurrence").asText("FREQ=DAILY"), from, to));
  }

  /**
   * Makes the person's dose rows from a little before {@code now} (so a slot still waiting for its
   * reminder is not lost) to 48 hours ahead. Never a row due before its schedule was made, so a
   * medicine added in the afternoon does not start with a missed morning dose. Safe to run twice.
   * Returns how many rows were added.
   */
  @Transactional
  public int expand(PersonScope p, Instant now) {
    if (!p.open()) return 0;
    PersonSettings ps = settings(p);
    Instant earliest = now.minus(reminderSettings(p.owner()).missedAfter());
    Instant to = now.plus(DoseExpander.AHEAD);
    List<NewDose> rows = new ArrayList<>();
    for (ObjectNode med : medicines(p)) {
      UUID id = UUID.fromString(med.path("id").asText());
      for (ObjectNode s : schedules(p, id)) {
        Optional<DoseExpander.Plan> plan = plan(med, s);
        if (plan.isEmpty()) continue;
        Instant made = instant(s.path("createdAt").asText());
        Instant from = made != null && made.isAfter(earliest) ? made : earliest;
        for (Instant due : DoseExpander.dueTimes(plan.get(), ps.zone(), from, to, days)) {
          rows.add(new NewDose(p.personId(), id, plan.get().scheduleId(), due));
        }
      }
    }
    return rows.isEmpty() ? 0 : doses.insertMissing(p.owner(), rows);
  }

  /** A course whose end date has passed becomes stopped (spec 4.2); returns how many stopped. */
  @Transactional
  public int stopEnded(PersonScope p, Instant now) {
    LocalDate today = now.atZone(settings(p).zone()).toLocalDate();
    int stopped = 0;
    for (ObjectNode med : medicines(p)) {
      if ("stopped".equals(med.path("status").asText()) || !med.hasNonNull("endDate")) continue;
      LocalDate end = LocalDate.parse(med.path("endDate").asText());
      if (!end.isBefore(today)) continue;
      stopOn(p, med, end, "End date reached", now);
      stopped++;
    }
    return stopped;
  }

  /** A dose was taken: the stock goes down by the dose, never below 0, and the refill check runs. */
  @Transactional
  public void taken(PersonScope p, UUID medicineId, BigDecimal dose, Instant now) {
    ObjectNode med = records.get(MED, p.owner(), medicineId).orElse(null);
    if (med == null || !med.path("trackStock").asBoolean(false) || !med.hasNonNull("stockUnits")) return;
    BigDecimal left = StockTracker.afterTaken(med.get("stockUnits").decimalValue(), dose);
    ObjectNode row = records.update(MED, p.owner(), medicineId, Json.obj().put("stockUnits", left)).orElse(med);
    refillCheck(p, row, reminderSettings(p.owner()), now);
  }

  /**
   * Sends the one refill reminder for the current stock when it covers the refill lead days of
   * doses or fewer. Nothing when no lead time is set. Returns whether it was sent now.
   */
  @Transactional
  public boolean refillCheck(PersonScope p, ObjectNode med, ReminderSettings s, Instant now) {
    if (!p.open() || !med.path("trackStock").asBoolean(false) || !med.hasNonNull("stockUnits")
        || !med.hasNonNull("refillLeadDays") || med.hasNonNull("refillRemindedAt")
        || !"active".equals(med.path("status").asText())) {
      return false;
    }
    UUID id = UUID.fromString(med.path("id").asText());
    LocalDate today = now.atZone(settings(p).zone()).toLocalDate();
    List<StockTracker.Use> uses = new ArrayList<>();
    for (ObjectNode sch : schedules(p, id)) {
      plan(med, sch).ifPresent(plan -> uses.add(new StockTracker.Use(plan, sch.path("doseUnits").decimalValue())));
    }
    BigDecimal needed = StockTracker.needed(uses, today, med.get("refillLeadDays").asInt(), days);
    if (!StockTracker.refillDue(med.get("stockUnits").decimalValue(), needed)) return false;
    records.update(MED, p.owner(), id, Json.obj().put("refillRemindedAt", now.toString()));
    ObjectNode reminder = Json.obj().put("personId", p.personId().toString()).put("kind", "refill")
        .put("refId", id.toString()).put("title", "Refill").put("dueAt", now.toString()).put("status", "sent")
        .put("sentAt", now.toString()).put("source", "manual");
    records.insert("vita_reminder", p.owner(), reminder);
    notifier.refill(p, id, s.showMedicineNames() ? med.path("name").asText() : null);
    return true;
  }

  /**
   * A record closed (spec 5.13, Fixture J): the future un-acted rows go and the reminders still
   * waiting are cancelled; the jobs skip the person from now on.
   */
  @Transactional
  public void closeRecord(UUID owner, UUID personId, Instant now) {
    doses.removeFutureOfPerson(owner, personId, now);
    Map<String, Object> where = new LinkedHashMap<>();
    where.put("personId", personId);
    where.put("status", "scheduled");
    for (ObjectNode r : records.list("vita_reminder", owner, where)) {
      records.update("vita_reminder", owner, UUID.fromString(r.path("id").asText()), Json.obj().put("status", "cancelled"));
    }
  }

  // ── Helpers ────────────────────────────────────────────────────────────────

  private void insertSchedule(PersonScope p, UUID medicineId, ObjectNode schedule, LocalDate from) {
    ObjectNode v = Json.obj().put("personId", p.personId().toString()).put("medicineId", medicineId.toString())
        .put("createdByUserId", p.caller().toString()).put("effectiveFrom", from.toString());
    v.set("times", schedule.get("times"));
    v.put("recurrence", schedule.path("recurrence").asText("FREQ=DAILY"));
    v.set("doseUnits", schedule.get("doseUnits"));
    v.put("doseUnit", schedule.path("doseUnit").asText("tablet"));
    v.put("withFood", schedule.path("withFood").asText("any"));
    records.insert(SCHED, p.owner(), v);
  }

  private void event(PersonScope p, UUID medicineId, String kind, LocalDate on, String from, String to,
                     String reason, String doctorId, String visitId, String source) {
    ObjectNode v = Json.obj().put("personId", p.personId().toString()).put("medicineId", medicineId.toString())
        .put("kind", kind).put("onDate", on.toString())
        .put("source", VitaRecordsUseCase.SOURCES.contains(source) ? source : "manual")
        .put("createdByUserId", p.caller().toString());
    if (from != null) v.put("fromText", cut(from, 200));
    if (to != null) v.put("toText", cut(to, 200));
    if (reason != null) v.put("reason", reason);
    if (doctorId != null) v.put("doctorId", doctorId);
    if (visitId != null) v.put("visitId", visitId);
    records.insert(EVENT, p.owner(), v);
  }

  /**
   * Checks a schedule body; with {@code base} only the fields sent change. Times are local "HH:mm"
   * in the person's zone, one to twelve, kept in order.
   */
  ObjectNode schedule(JsonNode body, ObjectNode base) {
    if (body == null || !body.isObject()) throw new ValidationException("schedule must be an object.");
    for (var it = body.fieldNames(); it.hasNext(); ) {
      String f = it.next();
      if (!Set.of("times", "recurrence", "doseUnits", "doseUnit", "withFood").contains(f)) {
        throw new ValidationException("schedule." + f + " cannot be set.");
      }
    }
    ObjectNode out = Json.obj();
    JsonNode times = body.has("times") ? body.get("times") : base == null ? null : base.get("times");
    if (times == null || !times.isArray() || times.isEmpty() || times.size() > 12) {
      throw new ValidationException("schedule.times needs one to twelve times such as \"08:00\".");
    }
    TreeSet<String> sorted = new TreeSet<>();
    for (JsonNode t : times) {
      if (!t.isTextual() || !TIME.matcher(t.asText()).matches()) {
        throw new ValidationException("Each time must be HH:mm, such as \"08:00\" or \"20:30\".");
      }
      sorted.add(t.asText());
    }
    ArrayNode arr = out.putArray("times");
    sorted.forEach(arr::add);
    String rule = body.has("recurrence") ? body.get("recurrence").asText(null)
        : base == null ? null : base.path("recurrence").asText(null);
    if (rule == null || rule.isBlank()) rule = "FREQ=DAILY";
    if (rule.length() > 200) throw new ValidationException("schedule.recurrence is too long.");
    try {
      days.validate(rule);
    } catch (RuntimeException e) {
      throw new ValidationException("schedule.recurrence must be a rule such as FREQ=DAILY or FREQ=WEEKLY;BYDAY=MO,TH.");
    }
    out.put("recurrence", rule);
    JsonNode units = body.has("doseUnits") ? body.get("doseUnits") : base == null ? null : base.get("doseUnits");
    BigDecimal u = units == null || units.isNull() ? BigDecimal.ONE : units.isNumber() ? units.decimalValue() : null;
    if (u == null || u.signum() <= 0 || u.compareTo(BigDecimal.valueOf(1000)) > 0 || u.stripTrailingZeros().scale() > 2) {
      throw new ValidationException("schedule.doseUnits must be a number above 0, with at most two decimals.");
    }
    out.put("doseUnits", u);
    String unit = body.has("doseUnit") ? body.get("doseUnit").asText(null) : base == null ? null : base.path("doseUnit").asText(null);
    if (unit == null) unit = "tablet";
    if (!UNITS.contains(unit)) throw new ValidationException("schedule.doseUnit must be one of " + String.join(", ", new TreeSet<>(UNITS)) + ".");
    out.put("doseUnit", unit);
    String food = body.has("withFood") ? body.get("withFood").asText(null) : base == null ? null : base.path("withFood").asText(null);
    if (food == null) food = "any";
    if (!FOOD.contains(food)) throw new ValidationException("schedule.withFood must be one of before, with, after, any.");
    out.put("withFood", food);
    return out;
  }

  /** One schedule field of a chat form, checked with the same rules as {@link #schedule}. */
  public static JsonNode checkScheduleField(String field, JsonNode v) {
    ObjectNode base = Json.obj();
    base.putArray("times").add("08:00");
    base.put("recurrence", "FREQ=DAILY").put("doseUnits", 1).put("doseUnit", "tablet").put("withFood", "any");
    ObjectNode body = Json.obj();
    body.set(field, v);
    VitaMedicineUseCase checker = new VitaMedicineUseCase(null, null, null, null, null, null, RecurrenceDays.DAILY, null, null);
    return checker.schedule(body, base).get(field);
  }

  private static boolean sameSchedule(ObjectNode a, ObjectNode b) {
    return a.path("times").equals(b.path("times"))
        && a.path("recurrence").asText().equals(b.path("recurrence").asText())
        && a.path("doseUnits").decimalValue().compareTo(b.path("doseUnits").decimalValue()) == 0
        && a.path("doseUnit").asText().equals(b.path("doseUnit").asText())
        && a.path("withFood").asText().equals(b.path("withFood").asText());
  }

  /** "1 tablet at 08:00, 20:00", with the strength first when given. */
  static String describe(String strength, JsonNode schedule) {
    List<String> parts = new ArrayList<>();
    if (strength != null && !strength.isBlank()) parts.add(strength);
    if (schedule != null && schedule.has("times")) {
      BigDecimal u = schedule.path("doseUnits").decimalValue();
      String unit = schedule.path("doseUnit").asText("tablet");
      boolean one = u.compareTo(BigDecimal.ONE) == 0;
      String word = switch (unit) {
        case "tablet" -> one ? "tablet" : "tablets";
        case "other" -> one ? "dose" : "doses";
        default -> unit;
      };
      List<String> times = new ArrayList<>();
      schedule.get("times").forEach(t -> times.add(t.asText()));
      String rule = schedule.path("recurrence").asText("FREQ=DAILY");
      parts.add(plain(u) + " " + word + " at " + String.join(", ", times) + ("FREQ=DAILY".equals(rule) ? "" : " (" + rule + ")"));
    }
    return parts.isEmpty() ? null : String.join(", ", parts);
  }

  static String plain(BigDecimal d) {
    if (d == null) return "0";
    BigDecimal s = d.stripTrailingZeros();
    return (s.scale() < 0 ? s.setScale(0) : s).toPlainString();
  }

  private void checkDates(ObjectNode values) {
    if (values.hasNonNull("startDate") && values.hasNonNull("endDate")
        && LocalDate.parse(values.get("endDate").asText()).isBefore(LocalDate.parse(values.get("startDate").asText()))) {
      throw new ValidationException("endDate is before startDate.");
    }
  }

  private UUID ref(PersonScope p, JsonNode body, String field, String table, String label) {
    if (body == null || !body.hasNonNull(field)) return null;
    UUID id;
    try {
      id = UUID.fromString(body.get(field).asText());
    } catch (IllegalArgumentException e) {
      throw new ValidationException(field + " must be an id.");
    }
    records.get(table, p.owner(), id).filter(r -> r.path("deletedAt").isNull())
        .filter(r -> !r.has("personId") || p.personId().toString().equals(r.path("personId").asText()))
        .orElseThrow(() -> new NotFoundException(label, id));
    return id;
  }

  private static String text(JsonNode body, String field, int max) {
    if (body == null || !body.hasNonNull(field)) return null;
    if (!body.get(field).isTextual()) throw new ValidationException(field + " must be text.");
    String s = body.get(field).asText().strip();
    if (s.isEmpty()) return null;
    if (s.length() > max) throw new ValidationException(field + " is too long (" + max + " at most).");
    return s;
  }

  private static String cut(String s, int max) {
    return s.length() <= max ? s : s.substring(0, max);
  }

  static Instant instant(String s) {
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
}
