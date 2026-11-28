package com.jizza.platform.usecase.vita.medicine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.vita.VitaDoseStore;
import com.jizza.engine.api.vita.VitaDoseStore.DoseRow;
import com.jizza.engine.api.vita.VitaDoseStore.Mark;
import com.jizza.engine.api.vita.VitaRecords;
import com.jizza.platform.usecase.vita.records.VitaRecordsUseCase;
import com.jizza.platform.usecase.vita.sharing.VitaScopeResolver;
import com.jizza.platform.usecase.vita.sharing.VitaScopeResolver.PersonScope;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The dose list and its buttons (spec 5.2, 5.12): Taken, Skip and Snooze for one dose or a whole
 * time slot, and adherence over a period. Anyone who may edit the record can mark; the first mark
 * wins (the row's version) and the next caller sees who marked it and when. A dose marked later
 * counts as taken, late: {@code takenAt} holds when it was taken, {@code actedAt} when the button
 * was pressed.
 */
@Service
public class VitaDoseUseCase {

  public static final List<String> ACTIONS = List.of("taken", "skipped", "snoozed");
  private static final int MAX_DAYS = 92;

  private final VitaDoseStore doses;
  private final VitaRecords records;
  private final VitaScopeResolver scopes;
  private final VitaMedicineUseCase medicines;
  private final AuditLog audit;

  public VitaDoseUseCase(VitaDoseStore doses, VitaRecords records, VitaScopeResolver scopes,
                         VitaMedicineUseCase medicines, AuditLog audit) {
    this.doses = doses;
    this.records = records;
    this.scopes = scopes;
    this.medicines = medicines;
    this.audit = audit;
  }

  // ── Reading ─────────────────────────────────────────────────────────────────

  /**
   * The person's slots from {@code from} to {@code to} (local dates in the person's zone, both
   * included; default today), each with its doses. On the web the medicine names are shown.
   */
  public ObjectNode list(UUID caller, UUID personId, LocalDate from, LocalDate to) {
    PersonScope p = scopes.person(caller, personId);
    var ps = medicines.settings(p);
    LocalDate today = LocalDate.now(ps.zone());
    LocalDate a = from == null ? today : from;
    LocalDate b = to == null ? a : to;
    if (b.isBefore(a)) throw new ValidationException("to is before from.");
    if (a.plusDays(MAX_DAYS).isBefore(b)) throw new ValidationException("Ask for " + MAX_DAYS + " days at most.");
    Instant start = a.atStartOfDay(ps.zone()).toInstant();
    Instant end = b.plusDays(1).atStartOfDay(ps.zone()).toInstant();
    ObjectNode out = Json.obj().put("personId", p.personId().toString()).put("zone", ps.zone().getId())
        .put("from", a.toString()).put("to", b.toString());
    ArrayNode slots = out.putArray("slots");
    slots(p, doses.forPerson(p.owner(), p.personId(), start, end), true, Instant.now()).forEach(slots::add);
    return out;
  }

  /** The slots of the given rows, grouped by due time, in order. */
  public List<ObjectNode> slots(PersonScope p, List<DoseRow> rows, boolean showNames, Instant now) {
    var ps = medicines.settings(p);
    Duration missedAfter = medicines.reminderSettings(p.owner()).missedAfter();
    Map<UUID, ObjectNode> meds = new HashMap<>();
    Map<UUID, ObjectNode> schedules = new HashMap<>();
    TreeMap<Instant, List<DoseRow>> bySlot = new TreeMap<>();
    for (DoseRow r : rows) bySlot.computeIfAbsent(r.dueAt(), k -> new ArrayList<>()).add(r);
    List<ObjectNode> out = new ArrayList<>();
    for (var e : bySlot.entrySet()) {
      out.add(slot(p, ps.zone(), e.getKey(), e.getValue(), meds, schedules, showNames, missedAfter, now));
    }
    return out;
  }

  ObjectNode slot(PersonScope p, ZoneId zone, Instant dueAt, List<DoseRow> rows, Map<UUID, ObjectNode> meds,
                  Map<UUID, ObjectNode> schedules, boolean showNames, Duration missedAfter, Instant now) {
    ObjectNode s = Json.obj().put("personId", p.personId().toString())
        .put("personName", p.person().displayName()).put("dueAt", dueAt.toString())
        .put("localTime", DateTimeFormatter.ofPattern("HH:mm").format(dueAt.atZone(zone)))
        .put("localDate", dueAt.atZone(zone).toLocalDate().toString()).put("zone", zone.getId());
    ArrayNode list = Json.mapper().createArrayNode();
    DoseRow lastMark = null;
    Instant snoozed = null;
    int taken = 0;
    int skipped = 0;
    int missed = 0;
    for (DoseRow r : rows) {
      ObjectNode med = meds.computeIfAbsent(r.medicineId(), id -> records.get("vita_medicine", p.owner(), id).orElse(Json.obj()));
      ObjectNode sch = schedules.computeIfAbsent(r.scheduleId(), id -> records.get("vita_medicine_schedule", p.owner(), id).orElse(Json.obj()));
      ObjectNode d = Json.obj().put("id", r.id().toString()).put("medicineId", r.medicineId().toString());
      if (showNames) {
        d.put("medicineName", med.path("name").asText(null));
        d.put("strength", med.path("strength").asText(null));
      } else {
        d.putNull("medicineName");
      }
      d.set("doseUnits", sch.path("doseUnits").isMissingNode() ? com.fasterxml.jackson.databind.node.NullNode.getInstance() : sch.get("doseUnits"));
      d.put("doseUnit", sch.path("doseUnit").asText(null)).put("withFood", sch.path("withFood").asText(null));
      boolean isSnoozed = "due".equals(r.status()) && r.snoozedUntil() != null && r.snoozedUntil().isAfter(now);
      d.put("status", isSnoozed ? "snoozed" : r.status());
      d.put("snoozedUntil", r.snoozedUntil() == null ? null : r.snoozedUntil().toString());
      d.put("takenAt", r.takenAt() == null ? null : r.takenAt().toString());
      d.put("actedAt", r.actedAt() == null ? null : r.actedAt().toString());
      d.put("markedBy", r.actedByUserId() == null ? null : r.actedByUserId().toString());
      d.put("late", late(r, missedAfter));
      d.put("version", r.version());
      list.add(d);
      switch (r.status()) {
        case "taken" -> taken++;
        case "skipped" -> skipped++;
        case "missed" -> missed++;
        default -> { }
      }
      if (r.marked() && (lastMark == null || r.actedAt().isAfter(lastMark.actedAt()))) lastMark = r;
      if (isSnoozed) snoozed = r.snoozedUntil();
    }
    int n = rows.size();
    String status = taken == n ? "taken" : skipped == n ? "skipped" : missed == n ? "missed"
        : taken + skipped + missed > 0 ? "partial" : snoozed != null ? "snoozed" : "due";
    s.put("status", status);
    s.put("snoozedUntil", snoozed == null ? null : snoozed.toString());
    s.put("markedBy", lastMark == null ? null : lastMark.actedByUserId().toString());
    s.put("markedAt", lastMark == null ? null : lastMark.actedAt().toString());
    s.put("missedAt", dueAt.plus(missedAfter).toString());
    s.set("doses", list);
    return s;
  }

  /** Marked after the slot would have been missed. */
  static boolean late(DoseRow r, Duration missedAfter) {
    return "taken".equals(r.status()) && r.actedAt() != null && r.actedAt().isAfter(r.dueAt().plus(missedAfter));
  }

  // ── Acting ─────────────────────────────────────────────────────────────────

  /** {@code action}: taken, skipped or snoozed; {@code takenAt}, {@code snoozeMinutes}, {@code note}. */
  public record Act(String action, Instant takenAt, Integer snoozeMinutes, String note) {
    public static Act of(JsonNode body) {
      if (body == null || !body.isObject()) throw new ValidationException("Send the action as an object.");
      String a = body.path("action").asText("");
      if (!ACTIONS.contains(a)) throw new ValidationException("action must be one of taken, skipped, snoozed.");
      Instant at = body.hasNonNull("takenAt") ? VitaRecordsUseCase.dateTime("takenAt", body.get("takenAt")) : null;
      Integer snooze = null;
      if (body.hasNonNull("snoozeMinutes")) {
        if (!body.get("snoozeMinutes").canConvertToInt() || body.get("snoozeMinutes").asInt() < 1
            || body.get("snoozeMinutes").asInt() > 240) {
          throw new ValidationException("snoozeMinutes must be a whole number from 1 to 240.");
        }
        snooze = body.get("snoozeMinutes").asInt();
      }
      String note = body.hasNonNull("note") ? body.get("note").asText().strip() : null;
      if (note != null && note.length() > 500) throw new ValidationException("note is too long (500 at most).");
      return new Act(a, at, snooze, note == null || note.isEmpty() ? null : note);
    }
  }

  /** Acts on one dose; returns its slot. */
  @Transactional
  public ObjectNode actDose(UUID caller, UUID doseId, JsonNode body) {
    return actDose(caller, doseId, Act.of(body), Instant.now(), true);
  }

  @Transactional
  public ObjectNode actDose(UUID caller, UUID doseId, Act act, Instant now, boolean showNames) {
    UUID owner = scopes.own(caller).ownerUserId();
    DoseRow row = doses.get(owner, doseId).orElseThrow(() -> new NotFoundException("Dose", doseId));
    PersonScope p = scopes.person(caller, row.personId()).requireOpen();
    act(p, row, act, now);
    return slotView(p, row.dueAt(), now, showNames);
  }

  /** Acts on every dose of a time slot that is still open; returns the slot. */
  @Transactional
  public ObjectNode actSlot(UUID caller, UUID personId, Instant dueAt, JsonNode body) {
    return actSlot(scopes.person(caller, personId).requireOpen(), dueAt, Act.of(body), Instant.now(), true);
  }

  @Transactional
  public ObjectNode actSlot(PersonScope p, Instant dueAt, Act act, Instant now, boolean showNames) {
    p.requireOpen();
    List<DoseRow> rows = doses.slot(p.owner(), p.personId(), dueAt);
    if (rows.isEmpty()) throw new NotFoundException("Medicines slot", dueAt.toString());
    for (DoseRow r : rows) act(p, r, act, now);
    return slotView(p, dueAt, now, showNames);
  }

  public ObjectNode slotView(PersonScope p, Instant dueAt, Instant now, boolean showNames) {
    List<DoseRow> rows = doses.slot(p.owner(), p.personId(), dueAt);
    if (rows.isEmpty()) throw new NotFoundException("Medicines slot", dueAt.toString());
    return slots(p, rows, showNames, now).get(0);
  }

  /**
   * One dose. Already taken or skipped: left as it is (the first mark wins). Missed: can still be
   * marked taken (late) or skipped. A snooze cannot pass the time the slot would be missed.
   * Returns whether this call changed the row.
   */
  boolean act(PersonScope p, DoseRow r, Act act, Instant now) {
    if (r.marked()) return false;
    Duration missedAfter = medicines.reminderSettings(p.owner()).missedAfter();
    Mark mark;
    switch (act.action()) {
      case "taken" -> {
        Instant takenAt = act.takenAt() == null ? now : act.takenAt();
        if (takenAt.isAfter(now.plusSeconds(60))) throw new ValidationException("takenAt is in the future.");
        BigDecimal dose = records.get("vita_medicine_schedule", p.owner(), r.scheduleId())
            .map(s -> s.path("doseUnits").decimalValue()).orElse(BigDecimal.ONE);
        mark = new Mark("taken", takenAt, dose, now, p.caller(), null, act.note());
      }
      case "skipped" -> mark = new Mark("skipped", null, null, now, p.caller(), null, act.note());
      case "snoozed" -> {
        if (!"due".equals(r.status())) throw new ValidationException("This dose was already missed: mark it taken or skipped.");
        int minutes = act.snoozeMinutes() != null ? act.snoozeMinutes() : medicines.reminderSettings(p.owner()).snoozeMinutes();
        Instant cap = r.dueAt().plus(missedAfter);
        Instant until = now.plus(Duration.ofMinutes(minutes));
        if (until.isAfter(cap)) until = cap;
        if (!until.isAfter(now)) throw new ValidationException("Too late to snooze: mark it taken or skipped.");
        mark = new Mark("due", null, null, null, null, until, act.note());
      }
      default -> throw new ValidationException("action must be one of taken, skipped, snoozed.");
    }
    if (!doses.mark(p.owner(), r.id(), r.version(), mark)) return false;
    if ("taken".equals(mark.status())) medicines.taken(p, r.medicineId(), mark.doseTaken(), now);
    audit.record(p.owner(), ActorType.USER, p.caller(), "vita.dose." + act.action(), r.id().toString(), null);
    return true;
  }

  // ── Adherence ──────────────────────────────────────────────────────────────

  /** Taken ÷ due from {@code from} to {@code to} (local dates, both included), overall and per medicine. */
  public ObjectNode adherence(UUID caller, UUID personId, LocalDate from, LocalDate to) {
    PersonScope p = scopes.person(caller, personId);
    var ps = medicines.settings(p);
    LocalDate today = LocalDate.now(ps.zone());
    LocalDate b = to == null ? today : to;
    LocalDate a = from == null ? b.minusDays(6) : from;
    if (b.isBefore(a)) throw new ValidationException("to is before from.");
    if (a.plusDays(366).isBefore(b)) throw new ValidationException("Ask for a year at most.");
    return adherence(p, a, b);
  }

  public ObjectNode adherence(PersonScope p, LocalDate from, LocalDate to) {
    var ps = medicines.settings(p);
    Duration missedAfter = medicines.reminderSettings(p.owner()).missedAfter();
    List<DoseRow> rows = doses.forPerson(p.owner(), p.personId(), from.atStartOfDay(ps.zone()).toInstant(),
        to.plusDays(1).atStartOfDay(ps.zone()).toInstant());
    ObjectNode out = Json.obj().put("from", from.toString()).put("to", to.toString());
    out.set("overall", Json.toNode(AdherenceCalculator.of(rows.stream().map(r -> dose(r, missedAfter)).toList())));
    ArrayNode per = out.putArray("medicines");
    Map<UUID, List<DoseRow>> byMed = new LinkedHashMap<>();
    rows.forEach(r -> byMed.computeIfAbsent(r.medicineId(), k -> new ArrayList<>()).add(r));
    byMed.entrySet().stream().sorted(Comparator.comparing(e -> e.getKey().toString())).forEach(e -> {
      ObjectNode m = Json.obj().put("medicineId", e.getKey().toString());
      records.get("vita_medicine", p.owner(), e.getKey()).ifPresent(med -> m.put("name", med.path("name").asText(null)));
      m.set("result", Json.toNode(AdherenceCalculator.of(e.getValue().stream().map(r -> dose(r, missedAfter)).toList())));
      per.add(m);
    });
    return out;
  }

  private static AdherenceCalculator.Dose dose(DoseRow r, Duration missedAfter) {
    return new AdherenceCalculator.Dose(r.status(), late(r, missedAfter));
  }
}
