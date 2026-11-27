package com.jizza.platform.usecase.vita.records;

import static com.jizza.platform.usecase.vita.records.VitaField.integer;
import static com.jizza.platform.usecase.vita.records.VitaField.of;
import static com.jizza.platform.usecase.vita.records.VitaField.oneOf;
import static com.jizza.platform.usecase.vita.records.VitaField.phone;
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
      text("advice", 2000), of("private", Type.BOOL), text("notes", 2000))),

  ALLERGY("vita_allergy", "Allergy", 50, List.of(
      text("substance", 120).need(), oneOf("category", "drug", "food", "environment", "other"),
      text("reaction", 300), oneOf("severity", "mild", "moderate", "severe", "unknown"),
      of("confirmedByDoctor", Type.BOOL)));

  public final String table;
  public final String label;
  /** How many live rows one person may have. */
  public final int maxPerPerson;
  public final Map<String, VitaField> fields;

  VitaTable(String table, String label, int maxPerPerson, List<VitaField> fields) {
    this.table = table;
    this.label = label;
    this.maxPerPerson = maxPerPerson;
    Map<String, VitaField> m = new LinkedHashMap<>();
    fields.forEach(f -> m.put(f.name(), f));
    this.fields = java.util.Collections.unmodifiableMap(m);
  }
}
