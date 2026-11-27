package com.jizza.platform.usecase.vita.quickstart;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.vita.VitaRecords;
import com.jizza.platform.usecase.vita.measure.VitaMeasurementUseCase;
import com.jizza.platform.usecase.vita.profile.VitaProfileUseCase;
import com.jizza.platform.usecase.vita.records.VitaRecordsUseCase;
import com.jizza.platform.usecase.vita.records.VitaTable;
import com.jizza.platform.usecase.vita.sharing.VitaScopeResolver;
import com.jizza.platform.usecase.vita.sharing.VitaScopeResolver.PersonScope;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Quick start (7.3): eight short steps per person, each saved as soon as it is sent, so it can be
 * left and resumed. Every step can be skipped. Answering a step again replaces what that step
 * added the first time; rows added elsewhere are left alone. Progress lives on the person's
 * profile ({@code vita_profile.quick_start}); the answers themselves become ordinary rows.
 */
@Service
public class VitaQuickStartUseCase {

  public static final List<String> STEPS = List.of("dob", "sex", "height_weight", "blood_group",
      "conditions", "allergies", "medicines", "contact");
  private static final int MAX_ITEMS = 10;
  private static final String SOURCE = "quick_start";

  private final VitaRecords records;
  private final VitaScopeResolver scopes;
  private final VitaProfileUseCase profiles;
  private final VitaRecordsUseCase forms;
  private final VitaMeasurementUseCase readings;
  private final AuditLog audit;

  public VitaQuickStartUseCase(VitaRecords records, VitaScopeResolver scopes,
                               VitaProfileUseCase profiles, VitaRecordsUseCase forms,
                               VitaMeasurementUseCase readings, AuditLog audit) {
    this.records = records;
    this.scopes = scopes;
    this.profiles = profiles;
    this.forms = forms;
    this.readings = readings;
    this.audit = audit;
  }

  public record Progress(List<String> completed, List<String> skipped, String next, boolean done,
                         int total) {}

  public record QuickStartView(UUID personId, Progress progress) {}

  @Transactional
  public QuickStartView get(UUID caller, UUID personId) {
    PersonScope p = scopes.personOrSelf(caller, personId);
    return new QuickStartView(p.personId(), progress(state(p)));
  }

  /** Saves one step's answers (or its skip) for one person and returns the progress. */
  @Transactional
  public QuickStartView save(UUID caller, UUID personId, String step, JsonNode answers, boolean skip) {
    if (step == null || !STEPS.contains(step)) {
      throw new ValidationException("step must be one of " + String.join(", ", STEPS) + ".");
    }
    PersonScope p = scopes.personOrSelf(caller, personId).requireOpen();
    ObjectNode state = state(p);
    if (skip) {
      mark(state, step, "skipped");
    } else {
      if (answers == null || !answers.isObject()) throw new ValidationException("Send the answers as an object.");
      ObjectNode created = state.with("created");
      List<String> ids = switch (step) {
        case "dob" -> profile(p, only(answers, "dob"));
        case "sex" -> profile(p, only(answers, "sexAtBirth"));
        case "blood_group" -> profile(p, only(answers, "bloodGroup"));
        case "height_weight" -> heightWeight(p, answers, ids(created, step));
        case "conditions" -> conditions(p, answers, ids(created, step));
        case "allergies" -> allergies(p, answers, ids(created, step));
        case "medicines" -> medicines(answers);
        case "contact" -> contact(p, answers, ids(created, step));
        default -> throw new IllegalStateException(step);
      };
      ArrayNode list = created.putArray(step);
      ids.forEach(list::add);
      mark(state, step, "completed");
    }
    write(p, state);
    audit.record(p.owner(), ActorType.USER, caller, "vita.quickstart." + (skip ? "skipped" : "saved"), step, null);
    return new QuickStartView(p.personId(), progress(state));
  }

  private List<String> profile(PersonScope p, ObjectNode fields) {
    profiles.save(p, fields, SOURCE);
    return List.of();
  }

  private List<String> heightWeight(PersonScope p, JsonNode a, List<String> earlier) {
    if (a.has("heightCm")) profiles.save(p, only(a, "heightCm"), SOURCE);
    removeEarlier(p, "vita_measurement", "Reading", earlier);
    if (!a.hasNonNull("weightKg")) return List.of();
    ObjectNode reading = JsonNodeFactory.instance.objectNode().put("kind", "weight").put("unit", "kg");
    reading.set("value", a.get("weightKg"));
    return List.of(readings.create(p, reading, SOURCE).path("id").asText());
  }

  private List<String> conditions(PersonScope p, JsonNode a, List<String> earlier) {
    removeEarlier(p, VitaTable.CONDITION.table, VitaTable.CONDITION.label, earlier);
    if (a.path("noneKnown").asBoolean(false)) {
      profiles.save(p, JsonNodeFactory.instance.objectNode().put("conditionsNoneKnown", true), SOURCE);
      return List.of();
    }
    List<String> ids = new ArrayList<>();
    for (JsonNode c : items(a, "conditions")) {
      ObjectNode body = JsonNodeFactory.instance.objectNode();
      body.set("name", c.path("name"));
      if (c.hasNonNull("template")) body.set("template", c.get("template"));
      if (c.hasNonNull("since")) body.set("since", c.get("since"));
      ids.add(forms.create(p, VitaTable.CONDITION, body, SOURCE).path("id").asText());
    }
    return ids;
  }

  private List<String> allergies(PersonScope p, JsonNode a, List<String> earlier) {
    removeEarlier(p, VitaTable.ALLERGY.table, VitaTable.ALLERGY.label, earlier);
    if (a.path("noneKnown").asBoolean(false)) {
      profiles.save(p, JsonNodeFactory.instance.objectNode().put("allergiesNoneKnown", true), SOURCE);
      return List.of();
    }
    List<String> ids = new ArrayList<>();
    for (JsonNode c : items(a, "allergies")) {
      ObjectNode body = JsonNodeFactory.instance.objectNode();
      for (String f : List.of("substance", "category", "reaction", "severity")) {
        if (c.hasNonNull(f)) body.set(f, c.get(f));
      }
      ids.add(forms.create(p, VitaTable.ALLERGY, body, SOURCE).path("id").asText());
    }
    return ids;
  }

  /** Medicines are built in Phase 1a: the step can only be answered "later" for now. */
  private static List<String> medicines(JsonNode a) {
    if (!a.path("later").asBoolean(false)) {
      throw new ValidationException("Medicines can be added in the next update; answer later or skip this step.");
    }
    return List.of();
  }

  private List<String> contact(PersonScope p, JsonNode a, List<String> earlier) {
    removeEarlier(p, VitaTable.CONTACT.table, VitaTable.CONTACT.label, earlier);
    ObjectNode body = JsonNodeFactory.instance.objectNode();
    for (String f : List.of("name", "relation", "phone")) if (a.has(f)) body.set(f, a.get(f));
    return List.of(forms.create(p, VitaTable.CONTACT, body, SOURCE).path("id").asText());
  }

  private void removeEarlier(PersonScope p, String table, String label, List<String> ids) {
    for (String id : ids) {
      try {
        forms.removeRow(p, table, label, UUID.fromString(id));
      } catch (RuntimeException gone) {
        // already removed elsewhere
      }
    }
  }

  private static List<JsonNode> items(JsonNode a, String field) {
    JsonNode list = a.path(field);
    if (!list.isArray()) throw new ValidationException(field + " must be a list, or say noneKnown.");
    if (list.size() > MAX_ITEMS) throw new ValidationException("Add at most " + MAX_ITEMS + " here; the rest can wait.");
    List<JsonNode> out = new ArrayList<>();
    list.forEach(out::add);
    return out;
  }

  private static ObjectNode only(JsonNode a, String field) {
    var it = a.fieldNames();
    while (it.hasNext()) {
      String f = it.next();
      if (!f.equals(field) && !(field.equals("heightCm") && f.equals("weightKg"))) {
        throw new ValidationException(f + " is not part of this step.");
      }
    }
    if (!a.hasNonNull(field)) throw new ValidationException(field + " is needed, or skip this step.");
    ObjectNode out = JsonNodeFactory.instance.objectNode();
    out.set(field, a.get(field));
    return out;
  }

  private static List<String> ids(ObjectNode created, String step) {
    List<String> out = new ArrayList<>();
    created.path(step).forEach(n -> out.add(n.asText()));
    return out;
  }

  private ObjectNode state(PersonScope p) {
    JsonNode qs = profiles.profileRow(p).path("quickStart");
    return qs.isObject() ? ((ObjectNode) qs).deepCopy() : JsonNodeFactory.instance.objectNode();
  }

  private void write(PersonScope p, ObjectNode state) {
    ObjectNode row = profiles.profileRow(p);
    records.update("vita_profile", p.owner(), UUID.fromString(row.path("id").asText()),
        JsonNodeFactory.instance.objectNode().set("quickStart", state));
  }

  private static void mark(ObjectNode state, String step, String as) {
    Set<String> completed = set(state, "completed");
    Set<String> skipped = set(state, "skipped");
    completed.remove(step);
    skipped.remove(step);
    ("completed".equals(as) ? completed : skipped).add(step);
    ArrayNode c = state.putArray("completed");
    STEPS.stream().filter(completed::contains).forEach(c::add);
    ArrayNode s = state.putArray("skipped");
    STEPS.stream().filter(skipped::contains).forEach(s::add);
  }

  private static Set<String> set(ObjectNode state, String field) {
    Set<String> out = new LinkedHashSet<>();
    state.path(field).forEach(n -> out.add(n.asText()));
    return out;
  }

  static Progress progress(ObjectNode state) {
    List<String> completed = new ArrayList<>(set(state, "completed"));
    List<String> skipped = new ArrayList<>(set(state, "skipped"));
    String next = STEPS.stream().filter(s -> !completed.contains(s) && !skipped.contains(s)).findFirst().orElse(null);
    return new Progress(completed, skipped, next, next == null, STEPS.size());
  }
}
