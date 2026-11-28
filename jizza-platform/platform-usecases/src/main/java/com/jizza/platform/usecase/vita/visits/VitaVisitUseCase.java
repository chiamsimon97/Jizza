package com.jizza.platform.usecase.vita.visits;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.vita.VitaRecords;
import com.jizza.platform.usecase.vita.home.VitaHomeUseCase;
import com.jizza.platform.usecase.vita.labs.LabNormaliser;
import com.jizza.platform.usecase.vita.labs.VitaLabCatalog;
import com.jizza.platform.usecase.vita.labs.VitaLabUseCase;
import com.jizza.platform.usecase.vita.medicine.VitaDoseUseCase;
import com.jizza.platform.usecase.vita.medicine.VitaMedicineUseCase;
import com.jizza.platform.usecase.vita.ranges.RangeSet;
import com.jizza.platform.usecase.vita.records.VitaRecordsUseCase;
import com.jizza.platform.usecase.vita.records.VitaTable;
import com.jizza.platform.usecase.vita.sharing.VitaScopeResolver;
import com.jizza.platform.usecase.vita.sharing.VitaScopeResolver.PersonScope;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Visit prep (spec 2.6, 7c) and the doctor-ready summary page (spec 5.8). Both are read-only views
 * built from the person's own rows: every line is a fact from a row, and nothing is advice. The
 * server makes no PDF (the client prints the page) and sends nothing anywhere.
 */
@Service
public class VitaVisitUseCase {

  public static final String NOTE = "Prepared by Vita from your own records. Not medical advice.";
  /** Days of readings and adherence the page shows (spec 5.8: "last 30 days"). */
  static final int DAYS = 30;
  /** How far back medicine changes go when there is no earlier visit: a product choice. */
  static final int CHANGES_DAYS_WITHOUT_VISIT = 180;
  static final Set<String> CHANGE_KINDS = Set.of("started", "dose_changed", "paused", "resumed", "stopped");
  private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);
  private static final DateTimeFormatter DAY_YEAR = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

  /** A reading line: kind and context, as the home trend reads them. */
  record ReadingKind(String kind, String context) {}

  static final List<ReadingKind> READINGS = List.of(new ReadingKind("bp", null),
      new ReadingKind("glucose", "before_meal"), new ReadingKind("glucose", "after_meal"),
      new ReadingKind("weight", null), new ReadingKind("heart_rate", null), new ReadingKind("spo2", null));

  private final VitaRecords records;
  private final VitaScopeResolver scopes;
  private final VitaRecordsUseCase forms;
  private final VitaMedicineUseCase medicines;
  private final VitaDoseUseCase doses;
  private final VitaHomeUseCase home;
  private final VitaLabUseCase labs;
  private final VitaLabCatalog catalog;
  private final AuditLog audit;

  public VitaVisitUseCase(VitaRecords records, VitaScopeResolver scopes, VitaRecordsUseCase forms,
                          VitaMedicineUseCase medicines, VitaDoseUseCase doses, VitaHomeUseCase home,
                          VitaLabUseCase labs, VitaLabCatalog catalog, AuditLog audit) {
    this.records = records;
    this.scopes = scopes;
    this.forms = forms;
    this.medicines = medicines;
    this.doses = doses;
    this.home = home;
    this.labs = labs;
    this.catalog = catalog;
    this.audit = audit;
  }

  // ── Visit prep ─────────────────────────────────────────────────────────────

  public ObjectNode prepare(UUID caller, UUID visitId) {
    return prepare(caller, visitId, Instant.now());
  }

  /**
   * One page for a visit: its questions, recent readings with averages, current medicines, the
   * medicine changes since the last visit (from the change history), and the latest lab values.
   * Nothing is saved and nothing is sent.
   */
  public ObjectNode prepare(UUID caller, UUID visitId, Instant now) {
    UUID owner = scopes.own(caller).ownerUserId();
    ObjectNode visit = records.get("vita_visit", owner, visitId).filter(r -> r.path("deletedAt").isNull())
        .orElseThrow(() -> new NotFoundException("Visit", visitId));
    PersonScope p = scopes.person(caller, UUID.fromString(visit.path("personId").asText()));
    LocalDate today = now.atZone(VitaRecordsUseCase.ZONE).toLocalDate();
    LocalDate visitDay = visit.hasNonNull("scheduledAt")
        ? Instant.parse(visit.get("scheduledAt").asText()).atZone(VitaRecordsUseCase.ZONE).toLocalDate() : today;

    // The last visit that took place before this one.
    LocalDate since = null;
    for (ObjectNode v : forms.live(p, VitaTable.VISIT)) {
      if (v.path("id").asText().equals(visitId.toString()) || !"done".equals(v.path("status").asText())
          || !v.hasNonNull("scheduledAt")) continue;
      LocalDate d = Instant.parse(v.get("scheduledAt").asText()).atZone(VitaRecordsUseCase.ZONE).toLocalDate();
      if (d.isBefore(visitDay) && (since == null || d.isAfter(since))) since = d;
    }

    ObjectNode out = Json.obj();
    out.set("visit", visit);
    out.set("person", person(p, today));
    doctor(owner, visit.path("doctorId").asText(null)).ifPresent(d -> out.set("doctor", d));
    ArrayNode questions = out.putArray("questions");
    for (JsonNode q : visit.path("questions")) {
      String t = q.isObject() ? q.path("text").asText("") : q.asText("");
      if (!t.isBlank()) questions.add(t);
    }
    out.set("readings", readings(caller, p, null));
    out.set("medicines", currentMedicines(p, null, false));
    if (since != null) out.put("changesSince", since.toString()).put("changesSinceLabel", "since the visit on " + DAY_YEAR.format(since));
    else out.put("changesSinceLabel", "in the last " + CHANGES_DAYS_WITHOUT_VISIT + " days");
    LocalDate from = since != null ? since : visitDay.minusDays(CHANGES_DAYS_WITHOUT_VISIT);
    out.set("medicineChanges", changes(p, from, visitDay, null));
    out.set("labs", latestLabs(p, visitDay, null));
    out.set("adherence", doses.adherence(p, today.minusDays(DAYS), today.minusDays(1)).path("overall"));
    out.put("note", NOTE).put("sent", false);
    audit.record(owner, ActorType.USER, caller, "vita.visit.prepared", visitId.toString(), null);
    return out;
  }

  // ── The doctor-ready summary ───────────────────────────────────────────────

  /**
   * The summary page's data (spec 5.8) for one person, or for one condition when {@code
   * conditionId} is given: profile, allergies, active conditions, current medicines with their
   * schedule and system, medicine changes, the last 30 days of key readings with averages, the
   * latest lab values with dates, adherence, and the questions for the next planned visit. Private
   * conditions and medicines are left out unless {@code includePrivate}.
   */
  public ObjectNode summary(UUID caller, UUID personId, UUID conditionId, boolean includePrivate) {
    return summary(caller, personId, conditionId, includePrivate, Instant.now());
  }

  public ObjectNode summary(UUID caller, UUID personId, UUID conditionId, boolean includePrivate, Instant now) {
    PersonScope p = scopes.person(caller, personId);
    LocalDate today = now.atZone(VitaRecordsUseCase.ZONE).toLocalDate();
    ObjectNode condition = conditionId == null ? null : forms.get(caller, personId, VitaTable.CONDITION, conditionId);
    ObjectNode out = Json.obj();
    out.set("person", person(p, today));
    if (condition != null) out.set("condition", condition);

    ArrayNode allergies = out.putArray("allergies");
    forms.live(p, VitaTable.ALLERGY).forEach(a -> allergies.addObject().put("substance", a.path("substance").asText())
        .put("severity", a.path("severity").asText(null)).put("reaction", a.path("reaction").asText(null)));
    ArrayNode conditions = out.putArray("conditions");
    forms.live(p, VitaTable.CONDITION).stream().filter(c -> "active".equals(c.path("status").asText()))
        .filter(c -> includePrivate || !c.path("private").asBoolean(false))
        .forEach(c -> conditions.addObject().put("name", c.path("name").asText()).put("since", c.path("since").asText(null)));

    out.set("medicines", currentMedicines(p, conditionId, includePrivate));
    out.set("medicineChanges", changes(p, today.minusDays(CHANGES_DAYS_WITHOUT_VISIT), today, conditionId));
    out.set("readings", readings(caller, p, condition));
    out.set("labs", latestLabs(p, today, condition));
    out.set("adherence", doses.adherence(p, today.minusDays(DAYS), today.minusDays(1)).path("overall"));
    forms.live(p, VitaTable.VISIT).stream().filter(v -> "planned".equals(v.path("status").asText()))
        .filter(v -> v.hasNonNull("scheduledAt") && !Instant.parse(v.get("scheduledAt").asText()).isBefore(now))
        .min(Comparator.comparing(v -> v.get("scheduledAt").asText()))
        .ifPresent(v -> {
          ObjectNode next = out.putObject("nextVisit").put("id", v.path("id").asText()).put("scheduledAt", v.get("scheduledAt").asText());
          ArrayNode qs = next.putArray("questions");
          for (JsonNode q : v.path("questions")) qs.add(q.isObject() ? q.path("text").asText("") : q.asText(""));
        });
    out.put("preparedOn", today.toString()).put("note", NOTE).put("sent", false);
    return out;
  }

  // ── Parts ──────────────────────────────────────────────────────────────────

  private ObjectNode person(PersonScope p, LocalDate today) {
    ObjectNode n = Json.obj().put("id", p.personId().toString()).put("name", p.person().displayName());
    LocalDate dob = p.person().dob();
    if (dob != null) n.put("dob", dob.toString()).put("age", Period.between(dob, today).getYears());
    records.list("vita_profile", p.owner(), Map.of("personId", p.personId())).stream().findFirst().ifPresent(pr -> {
      n.put("bloodGroup", pr.path("bloodGroup").asText(null)).put("sexAtBirth", pr.path("sexAtBirth").asText(null))
          .put("heightCm", pr.hasNonNull("heightCm") ? pr.get("heightCm").asText() : null);
    });
    return n;
  }

  private java.util.Optional<ObjectNode> doctor(UUID owner, String id) {
    if (id == null) return java.util.Optional.empty();
    return records.get("vita_doctor", owner, UUID.fromString(id)).filter(d -> d.path("deletedAt").isNull())
        .map(d -> Json.obj().put("id", d.path("id").asText()).put("name", d.path("name").asText())
            .put("speciality", d.path("speciality").asText(null)).put("clinic", d.path("clinic").asText(null)));
  }

  /** Active and paused medicines, with the schedule in force and the system of medicine. */
  private ArrayNode currentMedicines(PersonScope p, UUID conditionId, boolean includePrivate) {
    ArrayNode out = Json.mapper().createArrayNode();
    for (ObjectNode m : medicines.medicines(p)) {
      if (!Set.of("active", "paused").contains(m.path("status").asText())) continue;
      if (!includePrivate && m.path("private").asBoolean(false)) continue;
      if (conditionId != null && !conditionId.toString().equals(m.path("conditionId").asText(null))) continue;
      ObjectNode v = medicines.view(p, m);
      ObjectNode row = out.addObject().put("name", m.path("name").asText()).put("strength", m.path("strength").asText(null))
          .put("form", m.path("form").asText(null)).put("system", m.path("system").asText(null))
          .put("status", m.path("status").asText()).put("asNeeded", m.path("asNeeded").asBoolean(false));
      JsonNode s = v.path("schedule");
      if (s.isObject()) {
        row.putObject("schedule").<ObjectNode>set("times", s.path("times")).<ObjectNode>set("doseUnits", s.path("doseUnits"))
            .<ObjectNode>set("doseUnit", s.path("doseUnit")).<ObjectNode>set("withFood", s.path("withFood"))
            .set("recurrence", s.path("recurrence"));
      }
    }
    return out;
  }

  /**
   * Medicine changes from the change history, after {@code from} and up to {@code to}: one line
   * each, such as "16 Dec: telmisartan changed from 40 mg to 80 mg (Dr S Iyer)".
   */
  ArrayNode changes(PersonScope p, LocalDate from, LocalDate to, UUID conditionId) {
    Map<String, ObjectNode> meds = new LinkedHashMap<>();
    medicines.medicines(p).forEach(m -> meds.put(m.path("id").asText(), m));
    Map<String, String> doctors = new LinkedHashMap<>();
    records.list("vita_doctor", p.owner(), Map.of()).forEach(d -> doctors.put(d.path("id").asText(), d.path("name").asText()));
    List<ObjectNode> events = new ArrayList<>(records.list("vita_medicine_event", p.owner(), Map.of("personId", p.personId())));
    events.removeIf(e -> !e.path("deletedAt").isNull() || !CHANGE_KINDS.contains(e.path("kind").asText()) || !e.hasNonNull("onDate"));
    events.sort(Comparator.comparing((ObjectNode e) -> e.path("onDate").asText()).thenComparing(e -> e.path("createdAt").asText()));
    ArrayNode out = Json.mapper().createArrayNode();
    for (ObjectNode e : events) {
      LocalDate on = LocalDate.parse(e.get("onDate").asText());
      if (!on.isAfter(from) || on.isAfter(to)) continue;
      ObjectNode m = meds.get(e.path("medicineId").asText());
      if (m == null) continue;
      if (conditionId != null && !conditionId.toString().equals(m.path("conditionId").asText(null))) continue;
      String doctor = e.hasNonNull("doctorId") ? doctors.get(e.get("doctorId").asText()) : null;
      String line = DAY.format(on) + ": " + changeText(m.path("name").asText(), e)
          + (doctor == null ? "" : " (" + RangeSet.doctorTitle(doctor) + ")");
      out.addObject().put("onDate", on.toString()).put("kind", e.path("kind").asText()).put("medicine", m.path("name").asText())
          .put("fromText", e.path("fromText").asText(null)).put("toText", e.path("toText").asText(null))
          .put("doctorName", doctor).put("line", line);
    }
    return out;
  }

  static String changeText(String name, JsonNode e) {
    String fromText = e.path("fromText").asText(null);
    String toText = e.path("toText").asText(null);
    return switch (e.path("kind").asText()) {
      case "dose_changed" -> fromText != null && toText != null ? name + " changed from " + fromText + " to " + toText
          : name + " changed" + (toText != null ? " to " + toText : "");
      case "started" -> name + " started";
      case "paused" -> name + " paused";
      case "resumed" -> name + " resumed";
      case "stopped" -> name + " stopped";
      default -> name;
    };
  }

  /** The last 30 days of each key reading, with its average and how many were outside range. */
  private ArrayNode readings(UUID caller, PersonScope p, ObjectNode condition) {
    List<ReadingKind> kinds = READINGS;
    if (condition != null) {
      String t = condition.path("template").asText("generic");
      if (t.equals("diabetes_t2")) kinds = List.of(new ReadingKind("glucose", "before_meal"), new ReadingKind("glucose", "after_meal"));
      else if (t.equals("hypertension")) kinds = List.of(new ReadingKind("bp", null), new ReadingKind("heart_rate", null));
      else {
        List<ReadingKind> chosen = new ArrayList<>();
        condition.path("measures").forEach(k -> chosen.add(new ReadingKind(k.asText(), null)));
        kinds = chosen;
      }
    }
    ArrayNode out = Json.mapper().createArrayNode();
    for (ReadingKind k : kinds) {
      ObjectNode t;
      try {
        t = home.trend(caller, p.personId(), k.kind(), DAYS, k.context());
      } catch (ValidationException e) {
        continue;
      }
      JsonNode trend = t.path("trend");
      if (trend.path("count").asInt() == 0) continue;
      JsonNode all = trend.path("all");
      ObjectNode row = out.addObject().put("kind", k.kind()).put("context", k.context()).put("days", DAYS)
          .put("count", trend.path("count").asInt()).put("outside", all.path("outside").asLong());
      row.set("average", all.path("value").path("shown"));
      if (!all.path("value2").isNull() && !all.path("value2").isMissingNode()) row.set("average2", all.path("value2").path("shown"));
      row.set("last", trend.path("last"));
      row.set("range", t.path("range"));
    }
    return out;
  }

  /** The latest confirmed result of each test, up to {@code until}, with its date and lab flag. */
  private ArrayNode latestLabs(PersonScope p, LocalDate until, ObjectNode condition) {
    Set<String> only = null;
    if (condition != null) {
      String t = condition.path("template").asText("generic");
      only = switch (t) {
        case "diabetes_t2" -> Set.of("hba1c", "fasting_glucose", "pp_glucose", "creatinine", "egfr", "uacr",
            "total_cholesterol", "ldl", "hdl", "triglycerides");
        case "hypertension" -> Set.of("creatinine", "egfr", "total_cholesterol", "ldl", "hdl", "triglycerides");
        default -> {
          Set<String> s = new java.util.HashSet<>();
          condition.path("measures").forEach(m -> s.add(m.asText()));
          yield s;
        }
      };
    }
    Map<String, ObjectNode> latest = new LinkedHashMap<>();
    Map<String, LocalDate> when = new LinkedHashMap<>();
    LabNormaliser n = catalog.normaliser();
    for (ObjectNode rep : labs.reports(p)) {
      if (!"confirmed".equals(rep.path("status").asText())) continue;
      LocalDate d = VitaLabUseCase.date(rep);
      if (d == null || d.isAfter(until)) continue;
      for (ObjectNode r : labs.results(p, UUID.fromString(rep.path("id").asText()))) {
        String code = r.path("testCode").asText(null);
        if (code == null || (only != null && !only.contains(code))) continue;
        if (when.containsKey(code) && !d.isAfter(when.get(code))) continue;
        var test = n.byCode(code).orElse(null);
        int decimals = test == null ? 2 : test.decimals();
        ObjectNode row = Json.obj().put("code", code).put("name", test == null ? r.path("testNameAsPrinted").asText() : test.name())
            .put("date", d.toString()).put("flag", r.path("flag").asText());
        if (r.hasNonNull("valueCanonical")) {
          row.put("value", LabNormaliser.shown(r.get("valueCanonical").decimalValue(), decimals).toPlainString())
              .put("unit", r.path("unitCanonical").asText());
        } else {
          row.put("value", r.path("valueAsPrinted").asText(r.path("valueNum").asText(null))).put("unit", r.path("unit").asText(null));
        }
        row.put("rangeAsPrinted", r.path("refText").asText(null));
        latest.put(code, row);
        when.put(code, d);
      }
    }
    ArrayNode out = Json.mapper().createArrayNode();
    n.tests().forEach(t -> {
      if (latest.containsKey(t.code())) out.add(latest.get(t.code()));
    });
    return out;
  }
}
