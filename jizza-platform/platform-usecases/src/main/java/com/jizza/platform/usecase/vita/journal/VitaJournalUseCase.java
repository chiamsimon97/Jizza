package com.jizza.platform.usecase.vita.journal;

import static com.jizza.platform.usecase.vita.records.VitaField.integer;
import static com.jizza.platform.usecase.vita.records.VitaField.text;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.vita.VitaRecords;
import com.jizza.platform.usecase.vita.records.VitaField;
import com.jizza.platform.usecase.vita.records.VitaRecordsUseCase;
import com.jizza.platform.usecase.vita.sharing.VitaScopeResolver;
import com.jizza.platform.usecase.vita.sharing.VitaScopeResolver.PersonScope;
import com.jizza.platform.usecase.vita.sharing.VitaSection;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The daily journal (spec 4.2, 7.12): one entry per person per day with mood and energy (1-5),
 * pain (0-10), symptom words and a note. It is private: only the manager, and someone the manager
 * shared the person's {@code journal} section with, ever reads it. Vita reads nothing into it.
 */
@Service
public class VitaJournalUseCase {

  public static final String TABLE = "vita_journal_entry";
  static final int MAX_SYMPTOMS = 20;
  static final int SYMPTOM_MAX = 40;
  static final int MAX_DAYS = 400;

  /** The journal form's fields; symptoms are checked on their own. */
  public static final Map<String, VitaField> FIELDS = fields(
      integer("mood", 5), integer("energy", 5), integer("pain", 10), text("notes", 2000));
  public static final List<String> FORM_FIELDS = List.of("entryDate", "mood", "energy", "pain", "symptoms", "notes");

  private final VitaScopeResolver scopes;
  private final VitaRecords records;
  private final AuditLog audit;

  public VitaJournalUseCase(VitaScopeResolver scopes, VitaRecords records, AuditLog audit) {
    this.scopes = scopes;
    this.records = records;
    this.audit = audit;
  }

  /** The person's entries from {@code from} to {@code to}, newest first (the last 31 days by default). */
  public List<ObjectNode> list(UUID caller, UUID personId, LocalDate from, LocalDate to) {
    PersonScope p = scopes.person(caller, personId, VitaSection.JOURNAL, false);
    LocalDate end = to == null ? today() : to;
    LocalDate start = from == null ? end.minusDays(30) : from;
    if (start.isAfter(end)) throw new ValidationException("from must be on or before to.");
    if (ChronoUnit.DAYS.between(start, end) > MAX_DAYS) throw new ValidationException("Ask for at most " + MAX_DAYS + " days.");
    return live(p).stream().filter(r -> {
      LocalDate d = LocalDate.parse(r.path("entryDate").asText());
      return !d.isBefore(start) && !d.isAfter(end);
    }).sorted(Comparator.comparing((ObjectNode r) -> r.path("entryDate").asText()).reversed()).map(VitaJournalUseCase::view).toList();
  }

  public ObjectNode get(UUID caller, UUID personId, LocalDate date) {
    PersonScope p = scopes.person(caller, personId, VitaSection.JOURNAL, false);
    return find(p, date).map(VitaJournalUseCase::view).orElseThrow(() -> new NotFoundException("Journal entry", date.toString()));
  }

  /** Saves the day's entry: adds it, or changes the one already there. */
  @Transactional
  public ObjectNode put(UUID caller, UUID personId, LocalDate date, JsonNode body) {
    PersonScope p = scopes.person(caller, personId, VitaSection.JOURNAL, true).requireOpen();
    if (date.isAfter(today())) throw new ValidationException("A journal entry cannot be for a day still to come.");
    if (date.getYear() < 1900) throw new ValidationException("The date is out of range.");
    ObjectNode values = clean(body);
    Optional<ObjectNode> existing = find(p, date);
    ObjectNode row;
    if (existing.isPresent()) {
      VitaRecordsUseCase.requireOwnEntry(p, existing.get(), "Journal entry", UUID.fromString(existing.get().path("id").asText()));
      UUID id = UUID.fromString(existing.get().path("id").asText());
      row = values.isEmpty() ? existing.get()
          : records.update(TABLE, p.owner(), id, values).orElseThrow(() -> new NotFoundException("Journal entry", id));
      audit.record(p.owner(), ActorType.USER, caller, "vita.journal.updated", id.toString(), null);
    } else {
      boolean any = false;
      for (var it = values.elements(); it.hasNext(); ) any |= !it.next().isNull();
      if (!any) {
        throw new ValidationException("Fill in at least one thing for the day.");
      }
      values.put("personId", p.personId().toString());
      values.put("entryDate", date.toString());
      values.put("source", "manual");
      values.put("createdByUserId", p.caller().toString());
      row = records.insert(TABLE, p.owner(), values);
      audit.record(p.owner(), ActorType.USER, caller, "vita.journal.added", row.path("id").asText(), null);
    }
    return view(row);
  }

  @Transactional
  public void delete(UUID caller, UUID personId, LocalDate date) {
    PersonScope p = scopes.person(caller, personId, VitaSection.JOURNAL, true).requireOpen().requireManager();
    ObjectNode row = find(p, date).orElseThrow(() -> new NotFoundException("Journal entry", date.toString()));
    records.update(TABLE, p.owner(), UUID.fromString(row.path("id").asText()),
        Json.obj().put("deletedAt", java.time.Instant.now().toString()));
    audit.record(p.owner(), ActorType.USER, caller, "vita.journal.removed", row.path("id").asText(), null);
  }

  /** Checks a journal body; mood and energy run from 1 to 5. */
  public static ObjectNode clean(JsonNode body) {
    ObjectNode in = body != null && body.isObject() ? body.deepCopy() : null;
    if (in == null) throw new ValidationException("Send the fields as an object.");
    in.remove("entryDate");
    JsonNode symptoms = in.remove("symptoms");
    ObjectNode out = VitaRecordsUseCase.clean(FIELDS, in, false);
    for (String f : List.of("mood", "energy")) {
      if (out.hasNonNull(f) && out.get(f).asInt() < 1) throw new ValidationException(f + " must be from 1 to 5.");
    }
    if (symptoms != null) {
      if (symptoms.isNull()) {
        out.putNull("symptoms");
      } else {
        out.set("symptoms", symptoms(symptoms));
      }
    }
    return out;
  }

  /** Symptom words as typed: at most 20, each at most 40 characters, repeats dropped. */
  public static ArrayNode symptoms(JsonNode v) {
    if (!v.isArray() || v.size() > MAX_SYMPTOMS) {
      throw new ValidationException("symptoms must be a list of at most " + MAX_SYMPTOMS + " words.");
    }
    Set<String> seen = new LinkedHashSet<>();
    for (JsonNode n : v) {
      if (!n.isTextual()) throw new ValidationException("symptoms must be words.");
      String s = n.asText().strip();
      if (s.isEmpty()) continue;
      if (s.length() > SYMPTOM_MAX) throw new ValidationException("Each symptom is " + SYMPTOM_MAX + " characters at most.");
      seen.add(s);
    }
    ArrayNode out = Json.mapper().createArrayNode();
    seen.forEach(out::add);
    return out;
  }

  public static LocalDate date(String s) {
    try {
      return LocalDate.parse(s);
    } catch (DateTimeParseException | NullPointerException e) {
      throw new ValidationException("The date must be yyyy-mm-dd.");
    }
  }

  private List<ObjectNode> live(PersonScope p) {
    return records.list(TABLE, p.owner(), VitaRecordsUseCase.livePerson(p.personId()));
  }

  private Optional<ObjectNode> find(PersonScope p, LocalDate date) {
    Map<String, Object> where = new LinkedHashMap<>(VitaRecordsUseCase.livePerson(p.personId()));
    where.put("entryDate", date);
    return records.list(TABLE, p.owner(), where).stream().findFirst();
  }

  private static ObjectNode view(ObjectNode r) {
    ObjectNode v = r.deepCopy();
    v.remove("userId");
    v.remove("deletedAt");
    v.remove("sourceRef");
    if (!v.path("symptoms").isArray()) v.set("symptoms", Json.mapper().createArrayNode());
    return v;
  }

  private static LocalDate today() {
    return LocalDate.now(VitaRecordsUseCase.ZONE);
  }

  private static Map<String, VitaField> fields(VitaField... fs) {
    Map<String, VitaField> m = new LinkedHashMap<>();
    for (VitaField f : fs) m.put(f.name(), f);
    return java.util.Collections.unmodifiableMap(m);
  }
}
