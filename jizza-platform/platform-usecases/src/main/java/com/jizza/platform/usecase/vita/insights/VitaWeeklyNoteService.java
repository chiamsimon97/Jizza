package com.jizza.platform.usecase.vita.insights;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.enums.NotificationKind;
import com.jizza.engine.api.enums.NotificationPriority;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.finance.FinanceStore;
import com.jizza.engine.api.notification.NotificationBus;
import com.jizza.engine.api.notification.NotificationContext;
import com.jizza.engine.api.orchestrator.AgentDirectory;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.vita.VitaDoseStore;
import com.jizza.engine.api.vita.VitaRecords;
import com.jizza.engine.api.vita.VitaShares;
import com.jizza.platform.usecase.vita.insights.NoteCheck.Ref;
import com.jizza.platform.usecase.vita.insights.NoteCheck.Sentence;
import com.jizza.platform.usecase.vita.measure.VitaMeasurementUseCase;
import com.jizza.platform.usecase.vita.medicine.VitaMedicineUseCase;
import com.jizza.platform.usecase.vita.ranges.TargetEvaluator;
import com.jizza.platform.usecase.vita.ranges.VitaTargetService;
import com.jizza.platform.usecase.vita.records.VitaRecordsUseCase;
import com.jizza.platform.usecase.vita.sharing.VitaScopeResolver;
import com.jizza.platform.usecase.vita.sharing.VitaScopeResolver.PersonScope;
import com.jizza.platform.usecase.vita.tools.VitaToolProvider;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The weekly health note (spec 2.6, 5.12, Phase 4): per person, what changed this week (medicine
 * changes, lab reports added), missed doses, readings outside range, and the coming week's visits
 * and refills. It is assembled from rows: every sentence carries the ids of the rows it came from
 * and passes {@link NoteCheck} before it is kept, so a sentence with no row behind it or a number
 * not in those rows never reaches anyone. It names no cause, gives no advice and says nothing
 * about medicines beyond what the record holds.
 *
 * <p>The note is read by the record's manager and by the person themself when linked to it. Sunday
 * 19:00 India time the job keeps and sends this week's note once per open record; a closed record
 * gets none. The log holds counts and user ids only.
 */
@Service
public class VitaWeeklyNoteService {

  private static final Logger log = LoggerFactory.getLogger(VitaWeeklyNoteService.class);

  public static final String TABLE = "vita_weekly_note";
  public static final String DISCLAIMER = "Prepared by Vita from your own records. Not medical advice.";
  static final String NOTHING = "Nothing in the record needs attention this week.";
  static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);
  /** The weekly note looks one week ahead for visits, as the week it covers is one week. */
  static final Duration AHEAD = Duration.ofDays(7);

  private final VitaScopeResolver scopes;
  private final VitaRecords records;
  private final VitaDoseStore doses;
  private final VitaMeasurementUseCase measurements;
  private final VitaMedicineUseCase medicines;
  private final VitaTargetService targets;
  private final VitaShares shares;
  private final FinanceStore people;
  private final NotificationBus bus;
  private final AgentDirectory agents;

  public VitaWeeklyNoteService(VitaScopeResolver scopes, VitaRecords records, VitaDoseStore doses,
                               VitaMeasurementUseCase measurements, VitaMedicineUseCase medicines,
                               VitaTargetService targets, VitaShares shares, FinanceStore people,
                               NotificationBus bus, AgentDirectory agents) {
    this.scopes = scopes;
    this.records = records;
    this.doses = doses;
    this.measurements = measurements;
    this.medicines = medicines;
    this.targets = targets;
    this.shares = shares;
    this.people = people;
    this.bus = bus;
    this.agents = agents;
  }

  // ── Reading ─────────────────────────────────────────────────────────────────

  /**
   * The note for the week starting {@code weekStart} (a Monday; this week by default): the kept
   * one when it was sent, otherwise assembled now from the record as it stands.
   */
  public ObjectNode get(UUID caller, UUID personId, LocalDate weekStart) {
    PersonScope p = reader(caller, personId);
    ZoneId zone = medicines.settings(p).zone();
    Instant now = Instant.now();
    LocalDate week = weekStart == null ? monday(now.atZone(zone).toLocalDate()) : weekStart;
    if (week.getDayOfWeek() != DayOfWeek.MONDAY) throw new com.jizza.engine.api.error.ValidationException("weekStart must be a Monday.");
    Optional<ObjectNode> kept = kept(p, week);
    if (kept.isPresent()) {
      ObjectNode out = kept.get().path("note").deepCopy();
      out.put("sentAt", kept.get().path("sentAt").asText(null));
      return out;
    }
    if (!p.open()) {
      ObjectNode out = empty(p, week, zone);
      out.put("closed", true);
      return out;
    }
    Instant end = week.plusDays(7).atStartOfDay(zone).toInstant();
    return build(p, week, zone, end.isBefore(now) ? end : now);
  }

  /** Only the manager, or the person themself when linked to the record, reads the note. */
  private PersonScope reader(UUID caller, UUID personId) {
    PersonScope p = scopes.person(caller, personId);
    if (p.shared() && !p.grant().asSelf()) throw new NotFoundException("Person", personId);
    return p;
  }

  // ── Building ────────────────────────────────────────────────────────────────

  /** The note for one week, up to {@code until}, every sentence checked. */
  public ObjectNode build(PersonScope p, LocalDate week, ZoneId zone, Instant until) {
    Instant from = week.atStartOfDay(zone).toInstant();
    Map<String, ObjectNode> rows = new LinkedHashMap<>();
    List<Sentence> out = new ArrayList<>();
    changes(p, week, until, zone, rows, out);
    missed(p, from, until, rows, out);
    outside(p, from, until, zone, rows, out);
    visits(p, until, zone, rows, out);
    refills(p, rows, out);

    ObjectNode note = empty(p, week, zone);
    ArrayNode list = note.putArray("sentences");
    int rejected = 0;
    for (Sentence s : out) {
      String problem = NoteCheck.problem(s, rows, zone);
      if (problem != null) {
        rejected++;
        continue;
      }
      ObjectNode n = list.addObject().put("kind", s.kind()).put("text", s.text());
      ArrayNode refs = n.putArray("rows");
      s.rows().forEach(r -> refs.addObject().put("table", r.table()).put("id", r.id()));
    }
    if (rejected > 0) log.warn("Vita weekly note: {} sentences rejected for user {}", rejected, p.owner());
    note.put("empty", list.isEmpty());
    if (list.isEmpty()) note.put("message", NOTHING);
    note.put("until", until.toString());
    return note;
  }

  private ObjectNode empty(PersonScope p, LocalDate week, ZoneId zone) {
    ObjectNode note = Json.obj().put("personId", p.personId().toString()).put("personName", p.person().displayName())
        .put("weekStart", week.toString()).put("weekEnd", week.plusDays(6).toString()).put("zone", zone.getId())
        .put("disclaimer", DISCLAIMER);
    note.putArray("sentences");
    note.put("empty", true).put("message", NOTHING);
    return note;
  }

  /** Medicine changes on a day of the week, and lab reports added this week. */
  private void changes(PersonScope p, LocalDate week, Instant until, ZoneId zone, Map<String, ObjectNode> rows,
                       List<Sentence> out) {
    LocalDate last = LocalDate.ofInstant(until, zone);
    Map<String, ObjectNode> meds = new LinkedHashMap<>();
    for (ObjectNode m : medicines.medicines(p)) meds.put(m.path("id").asText(), m);
    List<ObjectNode> events = records.list("vita_medicine_event", p.owner(), VitaRecordsUseCase.livePerson(p.personId()));
    events.stream().sorted(Comparator.comparing((ObjectNode e) -> e.path("onDate").asText())).forEach(e -> {
      LocalDate on = LocalDate.parse(e.path("onDate").asText());
      String verb = switch (e.path("kind").asText()) {
        case "started" -> "was started";
        case "dose_changed" -> "had its dose or times changed";
        case "paused" -> "was paused";
        case "resumed" -> "was taken up again";
        case "stopped" -> "was stopped";
        default -> null;
      };
      ObjectNode med = meds.get(e.path("medicineId").asText());
      if (verb == null || med == null || on.isBefore(week) || on.isAfter(last)) return;
      rows.put(e.path("id").asText(), e);
      rows.put(med.path("id").asText(), med);
      out.add(new Sentence("medicine_change", med.path("name").asText("A medicine") + " " + verb + " on " + DAY.format(on) + ".",
          List.of(new Ref("vita_medicine_event", e.path("id").asText()), new Ref("vita_medicine", med.path("id").asText()))));
    });
    Instant from = week.atStartOfDay(zone).toInstant();
    for (ObjectNode r : records.list("vita_lab_report", p.owner(), VitaRecordsUseCase.livePerson(p.personId()))) {
      Instant added = instant(r.path("createdAt").asText(null));
      if (!"confirmed".equals(r.path("status").asText()) || added == null || added.isBefore(from) || added.isAfter(until)) continue;
      rows.put(r.path("id").asText(), r);
      String when = r.hasNonNull("collectedOn") ? " from " + DAY.format(LocalDate.parse(r.get("collectedOn").asText())) : "";
      out.add(new Sentence("lab_report", "A lab report" + when + " was added.", List.of(new Ref("vita_lab_report", r.path("id").asText()))));
    }
  }

  /** Doses due this week that were missed. */
  private void missed(PersonScope p, Instant from, Instant until, Map<String, ObjectNode> rows, List<Sentence> out) {
    List<Ref> refs = new ArrayList<>();
    for (VitaDoseStore.DoseRow d : doses.forPerson(p.owner(), p.personId(), from, until)) {
      if (!"missed".equals(d.status())) continue;
      ObjectNode row = Json.obj().put("id", d.id().toString()).put("dueAt", d.dueAt().toString()).put("status", d.status());
      rows.put(d.id().toString(), row);
      refs.add(new Ref("vita_dose_log", d.id().toString()));
    }
    if (refs.isEmpty()) return;
    out.add(new Sentence("missed_doses", refs.size() == 1 ? "1 dose was not marked as taken this week."
        : refs.size() + " doses were not marked as taken this week.", refs));
  }

  /** Readings this week outside the person's range or raising a red flag, by kind. */
  private void outside(PersonScope p, Instant from, Instant until, ZoneId zone, Map<String, ObjectNode> rows,
                       List<Sentence> out) {
    VitaTargetService.Context ctx = targets.context(p);
    Map<String, List<ObjectNode>> byKind = new LinkedHashMap<>();
    List<ObjectNode> all = new ArrayList<>(measurements.list(p, null));
    all.sort(Comparator.comparing((ObjectNode r) -> r.path("takenAt").asText("")));
    for (ObjectNode r : all) {
      Instant at = instant(r.path("takenAt").asText(null));
      if (at == null || at.isBefore(from) || at.isAfter(until)) continue;
      ObjectNode e = VitaMeasurementUseCase.withEvaluation(r, ctx);
      String status = e.path("evaluation").path("status").asText("");
      if (!TargetEvaluator.OUTSIDE_RANGE.equals(status) && !TargetEvaluator.RED_FLAG.equals(status)) continue;
      byKind.computeIfAbsent(r.path("kind").asText(), k -> new ArrayList<>()).add(r);
    }
    byKind.forEach((kind, list) -> {
      List<Ref> refs = new ArrayList<>();
      List<String> shown = new ArrayList<>();
      for (ObjectNode r : list) {
        rows.put(r.path("id").asText(), r);
        refs.add(new Ref("vita_measurement", r.path("id").asText()));
        Instant at = instant(r.path("takenAt").asText());
        shown.add(VitaMeasurementUseCase.shown(r).strip() + " on " + DAY.format(at.atZone(zone)));
      }
      String what = label(kind);
      String head = list.size() == 1 ? "1 " + what + " reading was outside the usual range: "
          : list.size() + " " + what + " readings were outside the usual range: ";
      out.add(new Sentence("outside_range", head + String.join(", ", shown) + ".", refs));
    });
  }

  /** Planned visits in the coming week. */
  private void visits(PersonScope p, Instant now, ZoneId zone, Map<String, ObjectNode> rows, List<Sentence> out) {
    Instant until = now.plus(AHEAD);
    List<ObjectNode> list = new ArrayList<>(records.list("vita_visit", p.owner(), VitaRecordsUseCase.livePerson(p.personId())));
    list.sort(Comparator.comparing((ObjectNode v) -> v.path("scheduledAt").asText("")));
    for (ObjectNode v : list) {
      Instant at = instant(v.path("scheduledAt").asText(null));
      if (!"planned".equals(v.path("status").asText()) || at == null || at.isBefore(now) || at.isAfter(until)) continue;
      rows.put(v.path("id").asText(), v);
      List<Ref> refs = new ArrayList<>(List.of(new Ref("vita_visit", v.path("id").asText())));
      String with = "";
      if (v.hasNonNull("doctorId")) {
        Optional<ObjectNode> doc = records.get("vita_doctor", p.owner(), UUID.fromString(v.get("doctorId").asText()));
        if (doc.isPresent() && doc.get().path("deletedAt").isNull()) {
          rows.put(doc.get().path("id").asText(), doc.get());
          refs.add(new Ref("vita_doctor", doc.get().path("id").asText()));
          String name = doc.get().path("name").asText();
          with = " with " + (name.matches("(?i)^dr\\.?\\s.*") ? name : "Dr " + name);
        }
      }
      out.add(new Sentence("visit", "A visit" + with + " is planned for " + DAY.format(at.atZone(zone)) + ".", refs));
    }
  }

  /** Medicines whose refill reminder has gone out and that have not been refilled since. */
  private void refills(PersonScope p, Map<String, ObjectNode> rows, List<Sentence> out) {
    for (ObjectNode m : medicines.medicines(p)) {
      if (!"active".equals(m.path("status").asText()) || !m.hasNonNull("refillRemindedAt")) continue;
      rows.put(m.path("id").asText(), m);
      out.add(new Sentence("refill", m.path("name").asText("A medicine") + " is running low: time to refill.",
          List.of(new Ref("vita_medicine", m.path("id").asText()))));
    }
  }

  // ── The weekly job ──────────────────────────────────────────────────────────

  /** What a weekly run did. */
  public record WeekRun(int people, int sent) {}

  /**
   * Keeps and sends this week's note for every open record that has not had it: to the manager
   * and to the person themself when linked. Safe to run twice.
   */
  public WeekRun weekly(Instant now, Set<UUID> only) {
    int seen = 0;
    int sent = 0;
    Set<UUID> owners = new LinkedHashSet<>(records.ownersWithRecords());
    owners.addAll(doses.ownersWithSchedules());
    if (only != null) owners.retainAll(only);
    for (UUID owner : owners) {
      try {
        for (var person : people.people(owner)) {
          if ("huf".equals(person.kind()) || !"active".equals(person.status())) continue;
          PersonScope p = scopes.person(owner, person.id());
          if (!p.open()) continue;
          seen++;
          ZoneId zone = medicines.settings(p).zone();
          LocalDate week = monday(now.atZone(zone).toLocalDate());
          if (kept(p, week).isPresent()) continue;
          ObjectNode note = build(p, week, zone, now);
          records.insert(TABLE, owner, Json.obj().put("personId", p.personId().toString())
              .put("weekStart", week.toString()).put("sentAt", now.toString()).set("note", note));
          notify(p, week);
          sent++;
        }
      } catch (RuntimeException e) {
        log.warn("Vita weekly note failed for user {}: {}", owner, e.getClass().getSimpleName());
      }
    }
    log.info("Vita weekly: {} people, {} notes sent", seen, sent);
    return new WeekRun(seen, sent);
  }

  private void notify(PersonScope p, LocalDate week) {
    ObjectNode ctx = Json.obj().put("type", "vita_weekly_note").put("personId", p.personId().toString())
        .put("weekStart", week.toString());
    boolean self = "self".equals(p.person().kind());
    Map<UUID, Boolean> to = new LinkedHashMap<>();
    to.put(p.owner(), self);
    for (VitaShares.Share s : shares.activeOf(p.owner(), p.personId())) {
      if (s.asSelf()) to.putIfAbsent(s.granteeUserId(), true);
    }
    to.forEach((user, thePerson) -> {
      UUID vita = agents.byKey(user, VitaToolProvider.AGENT_KEY).map(a -> a.id()).orElse(null);
      String body = thePerson ? "Your weekly health note is ready." : p.person().displayName() + "'s weekly health note is ready.";
      bus.raise(NotificationKind.INFO, vita, new NotificationContext(user, body, ctx.deepCopy(), null, null, null,
          Duration.ofDays(7), NotificationPriority.NORMAL));
    });
  }

  // ── Helpers ─────────────────────────────────────────────────────────────────

  private Optional<ObjectNode> kept(PersonScope p, LocalDate week) {
    Map<String, Object> where = new LinkedHashMap<>();
    where.put("personId", p.personId());
    where.put("weekStart", week);
    return records.list(TABLE, p.owner(), where).stream().findFirst();
  }

  static LocalDate monday(LocalDate d) {
    return d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
  }

  static String label(String kind) {
    return switch (kind) {
      case "bp" -> "blood pressure";
      case "glucose" -> "sugar";
      case "heart_rate" -> "heart rate";
      case "resting_heart_rate" -> "resting heart rate";
      case "spo2" -> "oxygen";
      default -> kind;
    };
  }

  static Instant instant(String s) {
    if (s == null) return null;
    try {
      return OffsetDateTime.parse(s).toInstant();
    } catch (DateTimeParseException e) {
      try {
        return Instant.parse(s);
      } catch (DateTimeParseException e2) {
        return null;
      }
    }
  }
}
