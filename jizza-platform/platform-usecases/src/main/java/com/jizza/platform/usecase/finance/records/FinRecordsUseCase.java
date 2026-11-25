package com.jizza.platform.usecase.finance.records;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.finance.FinanceRecords;
import com.jizza.engine.api.finance.FinanceStore;
import com.jizza.platform.usecase.finance.profile.FinProfileUseCase;
import com.jizza.platform.usecase.finance.sharing.FinScopeResolver;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Add, edit, list and remove the rows behind the Phase 1a forms (accounts, assets, loans,
 * policies…). Every call goes through the scope resolver (own data only), every field is checked
 * against {@link FinTable}, and every id a form points at must be the owner's own. The audit log
 * records what changed, never an amount or a name.
 */
@Service
public class FinRecordsUseCase {

  private static final BigDecimal MAX_MONEY = new BigDecimal("1000000000000000");
  private static final LocalDate EARLIEST = LocalDate.of(1900, 1, 1);
  private static final LocalDate LATEST = LocalDate.of(2200, 12, 31);

  private final FinanceRecords records;
  private final FinanceStore store;
  private final FinScopeResolver scopes;
  private final AuditLog audit;

  public FinRecordsUseCase(FinanceRecords records, FinanceStore store, FinScopeResolver scopes,
                           AuditLog audit) {
    this.records = records;
    this.store = store;
    this.scopes = scopes;
    this.audit = audit;
  }

  public List<ObjectNode> list(UUID caller, FinTable t, Map<String, Object> where) {
    var scope = scopes.own(caller);
    return records.list(t.table, scope.ownerUserId(), where == null ? Map.of() : where);
  }

  public ObjectNode get(UUID caller, FinTable t, UUID id) {
    var scope = scopes.own(caller);
    return records.get(t.table, scope.ownerUserId(), id)
        .orElseThrow(() -> new NotFoundException(t.label, id));
  }

  /** Adds a row. {@code fixed} holds fields the route decides (a payment's loan, say). */
  public ObjectNode create(UUID caller, FinTable t, JsonNode body, Map<String, Object> fixed) {
    var scope = scopes.own(caller);
    UUID owner = scope.ownerUserId();
    ObjectNode values = clean(owner, t, body, true);
    if (t.fields.containsKey("personId") && !values.hasNonNull("personId")) {
      values.put("personId", selfId(owner).toString());
    }
    if (fixed != null) fixed.forEach((k, v) -> values.put(k, String.valueOf(v)));
    if (t == FinTable.ASSET) assetDefaults(values);
    ObjectNode row = records.insert(t.table, owner, values);
    audit.record(owner, ActorType.USER, caller, "finance." + t.name().toLowerCase() + ".added",
        row.path("id").asText(), null);
    return row;
  }

  /** Changes the fields sent; a field sent as null is cleared unless the form needs it. */
  public ObjectNode update(UUID caller, FinTable t, UUID id, JsonNode body) {
    var scope = scopes.own(caller);
    UUID owner = scope.ownerUserId();
    get(caller, t, id);
    ObjectNode values = clean(owner, t, body, false);
    ObjectNode row = values.isEmpty() ? get(caller, t, id)
        : records.update(t.table, owner, id, values).orElseThrow(() -> new NotFoundException(t.label, id));
    audit.record(owner, ActorType.USER, caller, "finance." + t.name().toLowerCase() + ".updated",
        id.toString(), null);
    return row;
  }

  public void delete(UUID caller, FinTable t, UUID id) {
    var scope = scopes.own(caller);
    if (!records.delete(t.table, scope.ownerUserId(), id)) throw new NotFoundException(t.label, id);
    audit.record(scope.ownerUserId(), ActorType.USER, caller,
        "finance." + t.name().toLowerCase() + ".removed", id.toString(), null);
  }

  /** The id of the owner's "Me", creating the profile on the way when needed. */
  public UUID selfId(UUID owner) {
    store.ensureProfile(owner, FinProfileUseCase.SELF_NAME);
    return store.people(owner).stream().filter(p -> "self".equals(p.kind())).findFirst()
        .orElseThrow().id();
  }

  /** An asset's class and name follow from its kind when the form leaves them out. */
  static void assetDefaults(ObjectNode v) {
    String kind = v.path("kind").asText("other");
    if (!v.hasNonNull("assetClass")) {
      v.put("assetClass", switch (kind) {
        case "savings_balance", "cash" -> "cash";
        case "fd", "rd", "epf", "ppf", "nps", "ssy", "post_office" -> "debt";
        case "gold_physical", "gold_digital" -> "gold";
        default -> "other";
      });
    }
    if (!v.hasNonNull("name")) {
      v.put("name", switch (kind) {
        case "savings_balance" -> "Savings balance";
        case "fd" -> "Fixed deposit";
        case "rd" -> "Recurring deposit";
        case "epf" -> "EPF";
        case "ppf" -> "PPF";
        case "nps" -> "NPS";
        case "ssy" -> "Sukanya Samriddhi";
        case "post_office" -> "Post office savings";
        case "gold_physical" -> "Gold (physical)";
        case "gold_digital" -> "Gold (digital)";
        case "property" -> "Property";
        case "policy_surrender" -> "Policy surrender value";
        case "chit" -> "Chit fund";
        case "cash" -> "Cash";
        case "vehicle" -> "Vehicle";
        default -> "Other savings";
      });
    }
  }

  /** Fails as not found unless the person is one of the owner's own. */
  public void requirePerson(UUID owner, UUID personId) {
    if (store.person(owner, personId).isEmpty()) throw new NotFoundException("Person", personId);
  }

  /** Checks a form body against the table's fields and returns only what may be written. */
  public ObjectNode clean(UUID owner, FinTable t, JsonNode body, boolean creating) {
    if (body == null || !body.isObject()) throw new ValidationException("Send the fields as an object.");
    ObjectNode out = JsonNodeFactory.instance.objectNode();
    Iterator<Map.Entry<String, JsonNode>> it = body.fields();
    while (it.hasNext()) {
      var e = it.next();
      FieldSpec f = t.fields.get(e.getKey());
      if (f == null) throw new ValidationException(e.getKey() + " cannot be set here.");
      JsonNode v = e.getValue();
      if (v == null || v.isNull() || (v.isTextual() && v.asText().isBlank())) {
        if (f.required()) throw new ValidationException(f.name() + " is needed.");
        out.putNull(f.name());
        continue;
      }
      out.set(f.name(), checked(owner, f, v));
    }
    if (creating) {
      for (FieldSpec f : t.fields.values()) {
        if (f.required() && !out.hasNonNull(f.name()) && !"personId".equals(f.name())) {
          throw new ValidationException(f.name() + " is needed.");
        }
      }
    }
    return out;
  }

  private JsonNode checked(UUID owner, FieldSpec f, JsonNode v) {
    var nodes = JsonNodeFactory.instance;
    String name = f.name();
    switch (f.type()) {
      case TEXT -> {
        if (!v.isTextual()) throw new ValidationException(name + " must be text.");
        String s = v.asText().trim();
        if (s.length() > f.max()) throw new ValidationException(name + " is too long (" + f.max() + " at most).");
        return nodes.textNode(s);
      }
      case ENUM -> {
        if (!v.isTextual() || !f.values().contains(v.asText())) {
          throw new ValidationException(name + " must be one of " + String.join(", ", f.values()) + ".");
        }
        return v;
      }
      case MONEY, PCT, RATE, DECIMAL -> {
        BigDecimal d = number(name, v);
        if (d.signum() < 0) throw new ValidationException(name + " cannot be negative.");
        if (f.type() == FieldSpec.Type.MONEY && (d.compareTo(MAX_MONEY) >= 0 || d.stripTrailingZeros().scale() > 2)) {
          throw new ValidationException(name + " must be an amount in rupees with at most two decimals.");
        }
        if (f.type() == FieldSpec.Type.PCT && d.compareTo(BigDecimal.valueOf(100)) > 0) {
          throw new ValidationException(name + " must be between 0 and 100.");
        }
        if (f.type() == FieldSpec.Type.RATE && d.compareTo(BigDecimal.valueOf(1000)) > 0) {
          throw new ValidationException(name + " must be a yearly rate up to 1,000%.");
        }
        if (f.type() == FieldSpec.Type.DECIMAL && d.compareTo(MAX_MONEY) >= 0) {
          throw new ValidationException(name + " is too large.");
        }
        return nodes.numberNode(d);
      }
      case INT -> {
        if (!v.isIntegralNumber() || v.asLong() < 0 || v.asLong() > f.max()) {
          throw new ValidationException(name + " must be a whole number from 0 to " + f.max() + ".");
        }
        return nodes.numberNode(v.asInt());
      }
      case DATE -> {
        LocalDate d = date(name, v);
        return nodes.textNode(d.toString());
      }
      case BOOL -> {
        if (!v.isBoolean()) throw new ValidationException(name + " must be true or false.");
        return v;
      }
      case PERSON -> {
        UUID id = uuid(name, v);
        if (store.person(owner, id).isEmpty()) throw new NotFoundException("Person", id);
        return nodes.textNode(id.toString());
      }
      case PERSONS -> {
        if (!v.isArray()) throw new ValidationException(name + " must be a list of people.");
        if (v.size() > 50) throw new ValidationException(name + " has too many people.");
        ArrayNode out = nodes.arrayNode();
        for (JsonNode item : v) {
          UUID id = uuid(name, item);
          if (store.person(owner, id).isEmpty()) throw new NotFoundException("Person", id);
          out.add(id.toString());
        }
        return out;
      }
      case ACCOUNT -> {
        return owned(owner, name, v, "fin_account", "Account");
      }
      case ASSET -> {
        return owned(owner, name, v, "fin_asset", "Asset");
      }
      case CATEGORY -> {
        return owned(owner, name, v, "fin_category", "Category");
      }
      case FILE -> {
        UUID id = uuid(name, v);
        if (!store.ownsFile(owner, id)) throw new NotFoundException("File", id);
        return nodes.textNode(id.toString());
      }
      default -> throw new IllegalStateException("Unhandled field type " + f.type());
    }
  }

  private JsonNode owned(UUID owner, String name, JsonNode v, String table, String label) {
    UUID id = uuid(name, v);
    if (records.get(table, owner, id).isEmpty()) throw new NotFoundException(label, id);
    return JsonNodeFactory.instance.textNode(id.toString());
  }

  static BigDecimal number(String name, JsonNode v) {
    try {
      if (v.isNumber()) return v.decimalValue();
      if (v.isTextual()) return new BigDecimal(v.asText().trim().replace(",", ""));
    } catch (NumberFormatException ignored) {
      // falls through
    }
    throw new ValidationException(name + " must be a number.");
  }

  static LocalDate date(String name, JsonNode v) {
    try {
      LocalDate d = LocalDate.parse(v.asText());
      if (d.isBefore(EARLIEST) || d.isAfter(LATEST)) throw new ValidationException(name + " is out of range.");
      return d;
    } catch (DateTimeParseException e) {
      throw new ValidationException(name + " must be a date (yyyy-mm-dd).");
    }
  }

  public static UUID uuidOf(String name, JsonNode v) {
    return uuid(name, v);
  }

  static UUID uuid(String name, JsonNode v) {
    try {
      return UUID.fromString(v.asText());
    } catch (IllegalArgumentException e) {
      throw new ValidationException(name + " must be an id.");
    }
  }
}
