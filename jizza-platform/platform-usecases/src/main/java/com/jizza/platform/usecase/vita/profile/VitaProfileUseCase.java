package com.jizza.platform.usecase.vita.profile;

import static com.jizza.platform.usecase.vita.records.VitaField.of;
import static com.jizza.platform.usecase.vita.records.VitaField.oneOf;
import static com.jizza.platform.usecase.vita.records.VitaField.phone;
import static com.jizza.platform.usecase.vita.records.VitaField.text;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.crypto.EncryptionService;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.finance.FinanceStore;
import com.jizza.engine.api.finance.FinanceStore.PersonFields;
import com.jizza.engine.api.finance.FinanceStore.PersonRow;
import com.jizza.engine.api.vita.VitaRecords;
import com.jizza.engine.api.vita.VitaStore;
import com.jizza.platform.usecase.vita.config.VitaConfigService;
import com.jizza.platform.usecase.vita.records.VitaField;
import com.jizza.platform.usecase.vita.records.VitaRecordsUseCase;
import com.jizza.platform.usecase.vita.records.VitaTable;
import com.jizza.platform.usecase.vita.sharing.VitaScopeResolver;
import com.jizza.platform.usecase.vita.sharing.VitaScopeResolver.PersonScope;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A person's profile (spec 2.2, 7.4) and their emergency card. One profile row per person, made on
 * first visit. The date of birth lives on the shared people list; every other health fact lives in
 * {@code vita_profile}. The ABHA number is stored encrypted and only its last four digits are ever
 * shown, exported or given to Vita.
 */
@Service
public class VitaProfileUseCase {

  static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
  private static final Pattern HEIGHT = Pattern.compile("\\d{1,3}(\\.\\d)?");
  private static final Pattern ABHA = Pattern.compile("\\d{14}");

  /** The fields the profile form may set directly on {@code vita_profile}. */
  public static final Map<String, VitaField> FIELDS;
  static {
    Map<String, VitaField> m = new LinkedHashMap<>();
    for (VitaField f : List.of(
        oneOf("bloodGroup", "a_pos", "a_neg", "b_pos", "b_neg", "ab_pos", "ab_neg", "o_pos", "o_neg", "unknown"),
        oneOf("sexAtBirth", "female", "male", "intersex", "unknown"),
        of("organDonor", VitaField.Type.BOOL), text("preferredHospital", 160),
        phone("preferredHospitalPhone"), of("allergiesNoneKnown", VitaField.Type.BOOL),
        of("conditionsNoneKnown", VitaField.Type.BOOL),
        oneOf("reminderMode", "each_slot", "daily_check", "off"), text("notes", 2000))) {
      m.put(f.name(), f);
    }
    FIELDS = java.util.Collections.unmodifiableMap(m);
  }
  /** Fields with their own checks: height, time zone, ABHA number and date of birth. */
  public static final Set<String> SPECIAL = Set.of("heightCm", "timezone", "abhaNumber", "dob");

  private final VitaRecords records;
  private final VitaStore store;
  private final FinanceStore people;
  private final VitaScopeResolver scopes;
  private final VitaRecordsUseCase forms;
  private final VitaConfigService config;
  private final EncryptionService crypto;
  private final AuditLog audit;

  public VitaProfileUseCase(VitaRecords records, VitaStore store, FinanceStore people,
                            VitaScopeResolver scopes, VitaRecordsUseCase forms,
                            VitaConfigService config, EncryptionService crypto, AuditLog audit) {
    this.records = records;
    this.store = store;
    this.people = people;
    this.scopes = scopes;
    this.forms = forms;
    this.config = config;
    this.crypto = crypto;
    this.audit = audit;
  }

  /** A person as Vita shows them: never an HUF. */
  public record PersonView(UUID id, String displayName, String kind, LocalDate dob, Integer age,
                           String status, LocalDate statusOn) {
    static PersonView of(PersonRow p) {
      return new PersonView(p.id(), p.displayName(), p.kind(), p.dob(), VitaProfileUseCase.age(p.dob()), p.status(), p.statusOn());
    }
  }

  public record ProfileView(PersonView person, ObjectNode profile) {}

  /** The household people Vita can keep a record for ("Me" first, then the people managed). */
  public List<PersonView> people(UUID caller) {
    UUID owner = scopes.self(caller).owner();
    return people.people(owner).stream().filter(p -> !"huf".equals(p.kind()))
        .map(PersonView::of).toList();
  }

  @Transactional
  public ProfileView get(UUID caller, UUID personId) {
    PersonScope p = scopes.person(caller, personId);
    return new ProfileView(PersonView.of(p.person()), profileRow(p));
  }

  /** The person's profile row, made empty on first use; secret columns are never in it. */
  @Transactional
  public ObjectNode profileRow(PersonScope p) {
    UUID id = store.ensureProfile(p.owner(), p.personId(), p.caller());
    return records.get("vita_profile", p.owner(), id).orElseThrow(() -> new NotFoundException("Profile", id));
  }

  /** Saves the fields sent; a field sent as null is cleared. */
  @Transactional
  public ProfileView put(UUID caller, UUID personId, JsonNode body) {
    PersonScope p = scopes.person(caller, personId).requireOpen();
    save(p, body, "manual");
    return get(caller, personId);
  }

  /** Saves profile fields for a resolved person (the form, Quick start, a chat form's Save). */
  @Transactional
  public ObjectNode save(PersonScope p, JsonNode body, String source) {
    p.requireOpen();
    if (body == null || !body.isObject()) throw new ValidationException("Send the fields as an object.");
    ObjectNode plain = JsonNodeFactory.instance.objectNode();
    ObjectNode special = JsonNodeFactory.instance.objectNode();
    Iterator<Map.Entry<String, JsonNode>> it = body.fields();
    while (it.hasNext()) {
      var e = it.next();
      if (SPECIAL.contains(e.getKey())) special.set(e.getKey(), e.getValue());
      else plain.set(e.getKey(), e.getValue());
    }
    ObjectNode values = VitaRecordsUseCase.clean(FIELDS, plain, false);
    if (special.has("heightCm")) values.set("heightCm", checkHeight(special.get("heightCm")));
    if (special.has("timezone")) values.set("timezone", zone(special.get("timezone")));
    if (values.path("allergiesNoneKnown").asBoolean(false) && !forms.live(p, VitaTable.ALLERGY).isEmpty()) {
      throw new ValidationException("Remove the listed allergies before marking \"None known\".");
    }
    if (values.path("conditionsNoneKnown").asBoolean(false) && !forms.live(p, VitaTable.CONDITION).isEmpty()) {
      throw new ValidationException("Remove the listed conditions before marking \"None known\".");
    }
    String abha = special.has("abhaNumber") ? abha(special.get("abhaNumber")) : null;
    LocalDate dob = special.has("dob") && !special.get("dob").isNull() && !special.get("dob").asText().isBlank()
        ? VitaRecordsUseCase.pastDate("dob", special.get("dob")) : null;

    ObjectNode row = profileRow(p);
    UUID id = UUID.fromString(row.path("id").asText());
    if (special.has("abhaNumber")) {
      records.writeSecret("vita_profile", p.owner(), id, "abhaNumberEnc", abha == null ? null : crypto.encryptString(abha));
      values.put("abhaLast4", abha == null ? null : abha.substring(abha.length() - 4));
    }
    if (!values.isEmpty()) {
      values.put("version", row.path("version").asLong() + 1);
      if ("quick_start".equals(source) || "chat".equals(source)) values.put("source", source);
      records.update("vita_profile", p.owner(), id, values);
    }
    if (dob != null) {
      PersonRow person = p.person();
      people.updatePerson(p.owner(), p.personId(), new PersonFields(person.displayName(), person.kind(),
          person.type(), dob, person.earning(), person.dependent()));
    }
    audit.record(p.owner(), ActorType.USER, p.caller(), "vita.profile.updated", p.personId().toString(), null);
    return profileRow(p);
  }

  /** The emergency card's data (7.4); the client lays it out and prints it. */
  @Transactional
  public ObjectNode emergencyCard(UUID caller, UUID personId) {
    PersonScope p = scopes.person(caller, personId);
    ObjectNode profile = profileRow(p);
    var n = JsonNodeFactory.instance;
    ObjectNode card = n.objectNode();
    PersonView person = PersonView.of(p.person());
    card.put("personId", person.id().toString()).put("name", person.displayName());
    if (person.age() == null) card.putNull("age"); else card.put("age", person.age());
    card.set("bloodGroup", profile.get("bloodGroup"));
    card.set("organDonor", profile.get("organDonor"));
    card.set("preferredHospital", profile.get("preferredHospital"));
    card.set("preferredHospitalPhone", profile.get("preferredHospitalPhone"));

    List<ObjectNode> allergies = forms.live(p, VitaTable.ALLERGY).stream()
        .sorted(Comparator.comparingInt(a -> severityRank(a.path("severity").asText()))).toList();
    card.put("allergiesNoneKnown", profile.path("allergiesNoneKnown").asBoolean(false) && allergies.isEmpty());
    ArrayNode a = card.putArray("allergies");
    allergies.forEach(r -> a.addObject().put("substance", r.path("substance").asText())
        .put("severity", r.path("severity").asText()).set("reaction", r.get("reaction")));

    ArrayNode c = card.putArray("conditions");
    forms.live(p, VitaTable.CONDITION).stream()
        .filter(r -> "active".equals(r.path("status").asText()) && !r.path("private").asBoolean(false))
        .forEach(r -> c.addObject().put("name", r.path("name").asText()).set("since", r.get("since")));
    card.put("conditionsNoneKnown", profile.path("conditionsNoneKnown").asBoolean(false) && c.isEmpty());

    // Medicines arrive in Phase 1a; until then the card says so rather than showing an empty list.
    card.putArray("medicines");
    card.put("medicinesAvailable", false);

    ArrayNode contacts = card.putArray("emergencyContacts");
    List<ObjectNode> contactRows = forms.live(p, VitaTable.CONTACT);
    contactRows.forEach(r -> contacts.addObject().put("name", r.path("name").asText())
        .set("relation", r.get("relation")));
    for (int i = 0; i < contactRows.size(); i++) {
      ((ObjectNode) contacts.get(i)).put("phone", contactRows.get(i).path("phone").asText());
    }
    var numbers = config.item(caller, "emergency.numbers");
    card.set("emergencyNumbers", numbers.value());
    card.put("emergencyNumbersNeedVerification", numbers.needsVerification());

    card.put("updatedOn", latestUpdate(profile, allergies, forms.live(p, VitaTable.CONDITION), contactRows,
        Instant.now()));
    card.put("closed", !p.open());
    return card;
  }

  private static String latestUpdate(ObjectNode profile, List<ObjectNode> a, List<ObjectNode> c,
                                     List<ObjectNode> k, Instant fallback) {
    String best = profile.path("updatedAt").asText("");
    for (List<ObjectNode> rows : List.of(a, c, k)) {
      for (ObjectNode r : rows) {
        String u = r.path("updatedAt").asText("");
        if (u.compareTo(best) > 0) best = u;
      }
    }
    if (best.isEmpty()) return LocalDate.ofInstant(fallback, ZONE).toString();
    try {
      return java.time.OffsetDateTime.parse(best).atZoneSameInstant(ZONE).toLocalDate().toString();
    } catch (RuntimeException e) {
      return best.length() >= 10 ? best.substring(0, 10) : best;
    }
  }

  static int severityRank(String severity) {
    return switch (severity) {
      case "severe" -> 0;
      case "moderate" -> 1;
      case "mild" -> 2;
      default -> 3;
    };
  }

  static Integer age(LocalDate dob) {
    return dob == null ? null : Period.between(dob, LocalDate.now(ZONE)).getYears();
  }

  /** Height in cm: up to three digits and one decimal (an entry format, not a limit). */
  public static JsonNode checkHeight(JsonNode v) {
    if (v == null || v.isNull() || (v.isTextual() && v.asText().isBlank())) return JsonNodeFactory.instance.nullNode();
    String s = v.isNumber() ? v.decimalValue().stripTrailingZeros().toPlainString() : v.asText().strip();
    if (!HEIGHT.matcher(s).matches() || new BigDecimal(s).signum() <= 0) {
      throw new ValidationException("heightCm must be centimetres, such as 162 or 162.5.");
    }
    return JsonNodeFactory.instance.numberNode(new BigDecimal(s));
  }

  static JsonNode zone(JsonNode v) {
    if (v == null || v.isNull() || !v.isTextual() || v.asText().isBlank()) {
      return JsonNodeFactory.instance.textNode("Asia/Kolkata");
    }
    try {
      return JsonNodeFactory.instance.textNode(ZoneId.of(v.asText().strip()).getId());
    } catch (RuntimeException e) {
      throw new ValidationException("timezone must be a time zone such as Asia/Kolkata.");
    }
  }

  /** The 14-digit ABHA number, with any spaces or dashes taken out; null clears it. */
  static String abha(JsonNode v) {
    if (v == null || v.isNull() || (v.isTextual() && v.asText().isBlank())) return null;
    String digits = v.asText().replaceAll("[\\s-]", "");
    if (!ABHA.matcher(digits).matches()) throw new ValidationException("abhaNumber must be the 14-digit ABHA number.");
    return digits;
  }
}
