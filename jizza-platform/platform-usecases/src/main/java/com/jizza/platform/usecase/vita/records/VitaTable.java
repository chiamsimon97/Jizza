package com.jizza.platform.usecase.vita.records;

import static com.jizza.platform.usecase.vita.records.VitaField.decimal;
import static com.jizza.platform.usecase.vita.records.VitaField.integer;
import static com.jizza.platform.usecase.vita.records.VitaField.of;
import static com.jizza.platform.usecase.vita.records.VitaField.oneOf;
import static com.jizza.platform.usecase.vita.records.VitaField.phone;
import static com.jizza.platform.usecase.vita.records.VitaField.ref;
import static com.jizza.platform.usecase.vita.records.VitaField.text;

import com.jizza.platform.usecase.vita.records.VitaField.Type;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Vita's simple forms (spec 4.2): which table each writes and the fields it may set. */
public enum VitaTable {

  CONTACT("vita_emergency_contact", "Emergency contact", 5, List.of(
      text("name", 80).need(), text("relation", 40), phone("phone").need(), integer("sortOrder", 100))),

  CONDITION("vita_condition", "Condition", 50, List.of(
      text("name", 120).need(), oneOf("template", "diabetes_t2", "hypertension", "generic"),
      oneOf("status", "active", "resolved", "suspected"), of("since", Type.PAST_DATE),
      ref("doctorId", "vita_doctor"), text("advice", 2000), of("private", Type.BOOL), text("notes", 2000))),

  ALLERGY("vita_allergy", "Allergy", 50, List.of(
      text("substance", 120).need(), oneOf("category", "drug", "food", "environment", "other"),
      text("reaction", 300), oneOf("severity", "mild", "moderate", "severe", "unknown"),
      of("confirmedByDoctor", Type.BOOL))),

  /** Doctors, hospitals, clinics, labs and pharmacies: household-wide, no person (spec 4.2). */
  DOCTOR("vita_doctor", "Doctor", 300, List.of(
      oneOf("kind", "doctor", "hospital", "clinic", "lab", "pharmacy"), text("name", 120).need(),
      text("speciality", 80), text("clinic", 120), phone("phone"), text("address", 300),
      text("notes", 2000))),

  VISIT("vita_visit", "Visit", 2000, List.of(
      ref("doctorId", "vita_doctor"), of("scheduledAt", Type.DATETIME).need(),
      oneOf("status", "planned", "done", "cancelled"), text("reason", 300), text("notes", 4000),
      of("followUpOn", Type.DATE))),

  /**
   * A medicine as the user wrote it (spec 4.2): the name is free text, never completed. Its status
   * changes only through pause, resume and stop; its doses only through a change.
   */
  MEDICINE("vita_medicine", "Medicine", 200, List.of(
      text("name", 120).need(), text("brandName", 120), text("genericName", 120), text("strength", 40),
      oneOf("form", "tablet", "capsule", "syrup", "injection", "inhaler", "drops", "cream", "supply", "other"),
      oneOf("system", "allopathy", "ayurveda", "homeopathy", "siddha", "unani", "supplement", "other"),
      ref("conditionId", "vita_condition"), ref("prescribedBy", "vita_doctor"), of("startDate", Type.DATE),
      of("endDate", Type.DATE), of("asNeeded", Type.BOOL), of("trackStock", Type.BOOL),
      decimal("stockUnits", 100000), decimal("unitsPerPack", 10000), integer("refillLeadDays", 365),
      of("private", Type.BOOL), text("notes", 1000))),

  /** Visits, vaccinations, renewals, checks and the user's own reminders (spec 5.2, "Other reminders"). */
  REMINDER("vita_reminder", "Reminder", 500, List.of(
      oneOf("kind", "visit", "vaccination", "policy_renewal", "check_due", "custom").need(),
      text("title", 200), of("dueAt", Type.DATETIME).need(), text("recurrence", 200),
      oneOf("status", "scheduled", "done", "dismissed", "cancelled")));

  public final String table;
  public final String label;
  /** How many live rows one person may have. */
  public final int maxPerPerson;
  public final Map<String, VitaField> fields;

  /** Whether rows belong to the household rather than to one person. */
  public boolean household() {
    return this == DOCTOR;
  }

  VitaTable(String table, String label, int maxPerPerson, List<VitaField> fields) {
    this.table = table;
    this.label = label;
    this.maxPerPerson = maxPerPerson;
    Map<String, VitaField> m = new LinkedHashMap<>();
    fields.forEach(f -> m.put(f.name(), f));
    this.fields = java.util.Collections.unmodifiableMap(m);
  }
}
