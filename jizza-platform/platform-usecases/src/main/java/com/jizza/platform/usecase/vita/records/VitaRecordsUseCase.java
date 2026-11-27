package com.jizza.platform.usecase.vita.records;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.vita.VitaRecords;
import com.jizza.platform.usecase.vita.sharing.VitaScopeResolver;
import com.jizza.platform.usecase.vita.sharing.VitaScopeResolver.PersonScope;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Add, edit, list and remove the rows behind Vita's simple forms (contacts, conditions,
 * allergies). Every call goes through the scope resolver, every field is checked against
 * {@link VitaTable}, a row is reached only through its own person, and a removed row is kept with
 * {@code deleted_at} set (soft delete). The audit log records what changed, never a value or name.
 */
@Service
public class VitaRecordsUseCase {

  public static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
  /** Where a row came from (spec 4, common columns). */
  public static final Set<String> SOURCES = Set.of("manual", "quick_start", "upload", "chat", "mail",
      "health_connect", "apple_health");
  private static final LocalDate EARLIEST = LocalDate.of(1900, 1, 1);
  private static final Pattern PHONE = Pattern.compile("[0-9+()\\- ]{3,30}");

  private final VitaRecords records;
  private final VitaScopeResolver scopes;
  private final AuditLog audit;

  public VitaRecordsUseCase(VitaRecords records, VitaScopeResolver scopes, AuditLog audit) {
    this.records = records;
    this.scopes = scopes;
    this.audit = audit;
  }

  /** The person's live rows, oldest first (contacts by their order). */
  public List<ObjectNode> list(UUID caller, UUID personId, VitaTable t) {
    PersonScope p = scopes.person(caller, personId);
    return live(p, t);
  }

  public List<ObjectNode> live(PersonScope p, VitaTable t) {
    List<ObjectNode> rows = records.list(t.table, p.owner(), livePerson(p.personId()));
    if (t == VitaTable.CONTACT) {
      rows = rows.stream().sorted(java.util.Comparator.comparingInt(r -> r.path("sortOrder").asInt())).toList();
    }
    return rows;
  }

  public ObjectNode get(UUID caller, UUID personId, VitaTable t, UUID id) {
    return get(scopes.person(caller, personId), t, id);
  }

  ObjectNode get(PersonScope p, VitaTable t, UUID id) {
    return records.get(t.table, p.owner(), id)
        .filter(r -> p.personId().toString().equals(r.path("personId").asText()))
        .filter(r -> r.path("deletedAt").isNull())
        .orElseThrow(() -> new NotFoundException(t.label, id));
  }

  @Transactional
  public ObjectNode create(UUID caller, UUID personId, VitaTable t, JsonNode body, String source) {
    return create(scopes.person(caller, personId).requireOpen(), t, body, source);
  }

  /** Adds a row for a person already resolved (Quick start, chat forms). */
  @Transactional
  public ObjectNode create(PersonScope p, VitaTable t, JsonNode body, String source) {
    p.requireOpen();
    if (live(p, t).size() >= t.maxPerPerson) {
      throw new ValidationException("A record can hold at most " + t.maxPerPerson + " of these.");
    }
    ObjectNode values = clean(t.fields, body, true);
    values.put("personId", p.personId().toString());
    values.put("source", SOURCES.contains(source) ? source : "manual");
    values.put("createdByUserId", p.caller().toString());
    if (t == VitaTable.CONTACT && !values.hasNonNull("sortOrder")) values.put("sortOrder", live(p, t).size());
    ObjectNode row = records.insert(t.table, p.owner(), values);
    if (t == VitaTable.ALLERGY || t == VitaTable.CONDITION) clearNoneKnown(p, t);
    audit.record(p.owner(), ActorType.USER, p.caller(), "vita." + t.name().toLowerCase() + ".added",
        row.path("id").asText(), null);
    return row;
  }

  /** Changes the fields sent; a field sent as null is cleared unless the form needs it. */
  @Transactional
  public ObjectNode update(UUID caller, UUID personId, VitaTable t, UUID id, JsonNode body) {
    PersonScope p = scopes.person(caller, personId).requireOpen();
    get(p, t, id);
    ObjectNode values = clean(t.fields, body, false);
    ObjectNode row = values.isEmpty() ? get(p, t, id)
        : records.update(t.table, p.owner(), id, values).orElseThrow(() -> new NotFoundException(t.label, id));
    audit.record(p.owner(), ActorType.USER, caller, "vita." + t.name().toLowerCase() + ".updated",
        id.toString(), null);
    return row;
  }

  /** Soft delete: the row is kept with {@code deleted_at} and leaves every list. */
  @Transactional
  public void delete(UUID caller, UUID personId, VitaTable t, UUID id) {
    PersonScope p = scopes.person(caller, personId).requireOpen();
    removeRow(p, t.table, t.label, id);
    audit.record(p.owner(), ActorType.USER, caller, "vita." + t.name().toLowerCase() + ".removed",
        id.toString(), null);
  }

  /** Soft-deletes one of the person's rows in any Vita table. */
  public void removeRow(PersonScope p, String table, String label, UUID id) {
    records.get(table, p.owner(), id)
        .filter(r -> p.personId().toString().equals(r.path("personId").asText()))
        .filter(r -> r.path("deletedAt").isNull())
        .orElseThrow(() -> new NotFoundException(label, id));
    ObjectNode gone = JsonNodeFactory.instance.objectNode().put("deletedAt", Instant.now().toString());
    records.update(table, p.owner(), id, gone);
  }

  /** Adding a condition or an allergy means "none known" no longer holds. */
  private void clearNoneKnown(PersonScope p, VitaTable t) {
    String field = t == VitaTable.ALLERGY ? "allergiesNoneKnown" : "conditionsNoneKnown";
    records.list("vita_profile", p.owner(), Map.of("personId", p.personId())).stream()
        .filter(r -> r.path(field).asBoolean(false)).findFirst()
        .ifPresent(r -> records.update("vita_profile", p.owner(), UUID.fromString(r.path("id").asText()),
            JsonNodeFactory.instance.objectNode().put(field, false)));
  }

  /** The where-clause for a person's live rows: theirs, and not removed. */
  public static Map<String, Object> livePerson(UUID personId) {
    Map<String, Object> where = new java.util.LinkedHashMap<>();
    where.put("personId", personId);
    where.put("deletedAt", null);
    return where;
  }

  /** Checks a form body against the fields and returns only what may be written. */
  public static ObjectNode clean(Map<String, VitaField> fields, JsonNode body, boolean creating) {
    if (body == null || !body.isObject()) throw new ValidationException("Send the fields as an object.");
    ObjectNode out = JsonNodeFactory.instance.objectNode();
    Iterator<Map.Entry<String, JsonNode>> it = body.fields();
    while (it.hasNext()) {
      var e = it.next();
      VitaField f = fields.get(e.getKey());
      if (f == null) throw new ValidationException(e.getKey() + " cannot be set here.");
      JsonNode v = e.getValue();
      if (v == null || v.isNull() || (v.isTextual() && v.asText().isBlank())) {
        if (f.required()) throw new ValidationException(f.name() + " is needed.");
        out.putNull(f.name());
        continue;
      }
      out.set(f.name(), checked(f, v));
    }
    if (creating) {
      for (VitaField f : fields.values()) {
        if (f.required() && !out.hasNonNull(f.name())) throw new ValidationException(f.name() + " is needed.");
      }
    }
    return out;
  }

  static JsonNode checked(VitaField f, JsonNode v) {
    var nodes = JsonNodeFactory.instance;
    String name = f.name();
    switch (f.type()) {
      case TEXT -> {
        if (!v.isTextual()) throw new ValidationException(name + " must be text.");
        String s = v.asText().strip();
        if (s.length() > f.max()) throw new ValidationException(name + " is too long (" + f.max() + " at most).");
        return nodes.textNode(s);
      }
      case PHONE -> {
        String s = v.isNumber() ? v.asText() : v.isTextual() ? v.asText().strip() : null;
        if (s == null || !PHONE.matcher(s).matches() || s.chars().filter(Character::isDigit).count() < 3) {
          throw new ValidationException(name + " must be a phone number.");
        }
        return nodes.textNode(s);
      }
      case ENUM -> {
        if (!v.isTextual() || !f.values().contains(v.asText())) {
          throw new ValidationException(name + " must be one of " + String.join(", ", f.values()) + ".");
        }
        return v;
      }
      case PAST_DATE -> {
        return nodes.textNode(pastDate(name, v).toString());
      }
      case BOOL -> {
        if (!v.isBoolean()) throw new ValidationException(name + " must be true or false.");
        return v;
      }
      case INT -> {
        if (!v.isIntegralNumber() || v.asLong() < 0 || v.asLong() > f.max()) {
          throw new ValidationException(name + " must be a whole number from 0 to " + f.max() + ".");
        }
        return nodes.numberNode(v.asInt());
      }
      default -> throw new IllegalStateException("Unhandled field type " + f.type());
    }
  }

  /** A date on or before today in India, from 1900 on. */
  public static LocalDate pastDate(String name, JsonNode v) {
    LocalDate d;
    try {
      d = LocalDate.parse(v.asText());
    } catch (DateTimeParseException e) {
      throw new ValidationException(name + " must be a date (yyyy-mm-dd).");
    }
    if (d.isBefore(EARLIEST)) throw new ValidationException(name + " is out of range.");
    if (d.isAfter(LocalDate.now(ZONE))) throw new ValidationException(name + " is in the future.");
    return d;
  }
}
