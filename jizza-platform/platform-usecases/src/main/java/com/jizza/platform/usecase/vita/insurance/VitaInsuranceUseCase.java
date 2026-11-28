package com.jizza.platform.usecase.vita.insurance;

import static com.jizza.platform.usecase.vita.records.VitaField.decimal;
import static com.jizza.platform.usecase.vita.records.VitaField.of;
import static com.jizza.platform.usecase.vita.records.VitaField.oneOf;
import static com.jizza.platform.usecase.vita.records.VitaField.ref;
import static com.jizza.platform.usecase.vita.records.VitaField.text;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.crypto.EncryptionService;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.finance.FinanceRecords;
import com.jizza.engine.api.finance.FinanceStore;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.vita.VitaRecords;
import com.jizza.platform.usecase.vita.config.VitaConfigService;
import com.jizza.platform.usecase.vita.medicine.ReminderSettings;
import com.jizza.platform.usecase.vita.records.VitaField;
import com.jizza.platform.usecase.vita.records.VitaField.Type;
import com.jizza.platform.usecase.vita.records.VitaRecordsUseCase;
import com.jizza.platform.usecase.vita.sharing.VitaScopeResolver;
import com.jizza.platform.usecase.vita.sharing.VitaScopeResolver.PersonScope;
import com.jizza.platform.usecase.vita.sharing.VitaSection;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Health insurance and claims (spec 4.3, 7.15, Phase 2b). Vita owns the health kinds of Kai's
 * {@code fin_insurance_policy} and reads and writes them without the finance switch; the
 * health-only fields live in {@code vita_policy_detail} and claims in {@code vita_claim}.
 *
 * <p>Policies are the household's: only the manager adds, changes or removes one. A helper whose
 * share covers a person's {@code insurance} section sees the policies that cover that person,
 * without the household's "used" figures (they can come from someone else's stay), and sees and
 * adds that person's claims. Policy and card numbers are stored encrypted and only ever shown
 * masked. Vita never names, compares or recommends an insurer or a scheme.
 */
@Service
public class VitaInsuranceUseCase {

  public static final String POLICY_TABLE = "fin_insurance_policy";
  public static final String DETAIL_TABLE = "vita_policy_detail";
  public static final String CLAIM_TABLE = "vita_claim";
  /** When on the reminder's day the renewal reminder goes out. */
  static final LocalTime REMINDER_AT = LocalTime.of(9, 0);
  private static final int MAX_POLICIES = 50;
  private static final int MAX_CLAIMS = 500;
  private static final int MONEY_MAX = 1_000_000_000;
  /** A policy or card number as printed: letters, digits, spaces, / and -. */
  private static final Pattern NUMBER = Pattern.compile("[A-Za-z0-9/\\- ]{4,40}");

  static final Map<String, VitaField> POLICY_FIELDS = fields(
      oneOf("kind", PolicyYear.HEALTH_KINDS.stream().sorted().toArray(String[]::new)).need(),
      text("providerName", 120), decimal("sumAssured", MONEY_MAX), decimal("deductible", MONEY_MAX),
      decimal("premium", MONEY_MAX), oneOf("premiumFrequency", "monthly", "quarterly", "yearly", "single"),
      of("renewalDate", Type.DATE), of("policyEndDate", Type.DATE), of("employerProvided", Type.BOOL));

  static final Map<String, VitaField> DETAIL_FIELDS = fields(
      text("tpaName", 120), ref("cashlessCardDocumentId", "vita_document"),
      decimal("roomRentLimit", MONEY_MAX), decimal("copayPct", 100), VitaField.lines("waitingPeriods", 20),
      text("networkHospitalsNote", 2000));

  static final Map<String, VitaField> CLAIM_FIELDS = fields(
      ref("eventId", "vita_event"), ref("visitId", "vita_visit"), of("admittedOn", Type.DATE).need(),
      of("dischargedOn", Type.DATE), decimal("amountClaimed", MONEY_MAX), decimal("amountPaid", MONEY_MAX),
      oneOf("status", "draft", "filed", "query", "settled", "rejected"),
      VitaField.refs("documentIds", "vita_document", 20), text("notes", 2000));

  private final VitaScopeResolver scopes;
  private final FinanceStore people;
  private final FinanceRecords policies;
  private final VitaRecords records;
  private final VitaRecordsUseCase forms;
  private final VitaConfigService config;
  private final EncryptionService crypto;
  private final AuditLog audit;

  public VitaInsuranceUseCase(VitaScopeResolver scopes, FinanceStore people, FinanceRecords policies,
                              VitaRecords records, VitaRecordsUseCase forms, VitaConfigService config,
                              EncryptionService crypto, AuditLog audit) {
    this.scopes = scopes;
    this.people = people;
    this.policies = policies;
    this.records = records;
    this.forms = forms;
    this.config = config;
    this.crypto = crypto;
    this.audit = audit;
  }

  // ── Policies ────────────────────────────────────────────────────────────────

  /**
   * The household's health policies, or, with a person named, the policies covering that person
   * (own or through a share of their insurance section).
   */
  public List<ObjectNode> policies(UUID caller, UUID personId) {
    if (personId == null) {
      UUID owner = scopes.own(caller).ownerUserId();
      return views(owner, healthPolicies(owner), true);
    }
    PersonScope p = scopes.person(caller, personId, VitaSection.INSURANCE, false);
    List<ObjectNode> covering = healthPolicies(p.owner()).stream()
        .filter(r -> covered(r).contains(p.personId().toString())).toList();
    return views(p.owner(), covering, p.manager());
  }

  public ObjectNode policy(UUID caller, UUID id) {
    Reached r = reach(caller, id);
    return views(r.owner(), List.of(r.row()), r.manager()).get(0);
  }

  @Transactional
  public ObjectNode addPolicy(UUID caller, JsonNode body) {
    UUID owner = scopes.own(caller).ownerUserId();
    if (healthPolicies(owner).size() >= MAX_POLICIES) {
      throw new ValidationException("A household can hold at most " + MAX_POLICIES + " health policies.");
    }
    ObjectNode in = body != null && body.isObject() ? body.deepCopy() : Json.obj();
    UUID holder = in.hasNonNull("personId") ? uuid("personId", in.get("personId")) : null;
    in.remove("personId");
    JsonNode coveredIn = in.remove("coveredPersonIds");
    ObjectNode values = VitaRecordsUseCase.clean(POLICY_FIELDS, in, true);
    PersonScope p = holder == null ? scopes.self(caller) : scopes.person(caller, holder);
    p.requireOpen();
    values.put("personId", p.personId().toString());
    values.set("coveredPersonIds", coveredIds(owner, coveredIn, p.personId()));
    sumNeeded(values, null);
    ObjectNode row = policies.insert(POLICY_TABLE, owner, values);
    audit.record(owner, ActorType.USER, caller, "vita.policy.added", row.path("id").asText(), null);
    renewal(owner, row, ReminderSettings.of(config, owner), Instant.now());
    return policy(caller, UUID.fromString(row.path("id").asText()));
  }

  @Transactional
  public ObjectNode updatePolicy(UUID caller, UUID id, JsonNode body) {
    Reached r = reach(caller, id);
    if (!r.manager()) throw new NotFoundException("Policy", id);
    ObjectNode in = body != null && body.isObject() ? body.deepCopy() : Json.obj();
    UUID holder = in.hasNonNull("personId") ? uuid("personId", in.get("personId")) : null;
    in.remove("personId");
    JsonNode coveredIn = in.has("coveredPersonIds") ? in.remove("coveredPersonIds") : null;
    ObjectNode values = VitaRecordsUseCase.clean(POLICY_FIELDS, in, false);
    UUID holderId = UUID.fromString(r.row().path("personId").asText());
    if (holder != null) {
      holderId = scopes.person(caller, holder).personId();
      values.put("personId", holderId.toString());
    }
    if (coveredIn != null) values.set("coveredPersonIds", coveredIds(r.owner(), coveredIn, holderId));
    sumNeeded(values, r.row());
    ObjectNode row = values.isEmpty() ? r.row()
        : policies.update(POLICY_TABLE, r.owner(), id, values).orElseThrow(() -> new NotFoundException("Policy", id));
    audit.record(r.owner(), ActorType.USER, caller, "vita.policy.updated", id.toString(), null);
    renewal(r.owner(), row, ReminderSettings.of(config, r.owner()), Instant.now());
    return policy(caller, id);
  }

  /** Removes a policy with its detail and claims, and cancels its renewal reminder. */
  @Transactional
  public void deletePolicy(UUID caller, UUID id) {
    Reached r = reach(caller, id);
    if (!r.manager()) throw new NotFoundException("Policy", id);
    cancelRenewal(r.owner(), id);
    policies.delete(POLICY_TABLE, r.owner(), id);
    audit.record(r.owner(), ActorType.USER, caller, "vita.policy.removed", id.toString(), null);
  }

  /** The policy's health-only fields, numbers masked. */
  public ObjectNode detail(UUID caller, UUID id) {
    Reached r = reach(caller, id);
    return detailView(detailRow(r.owner(), id).orElse(null), id);
  }

  /**
   * Sets the health-only fields. {@code policyNumber} and {@code cardNumber} are encrypted on the
   * way in and never come back out; null clears one.
   */
  @Transactional
  public ObjectNode putDetail(UUID caller, UUID id, JsonNode body) {
    Reached r = reach(caller, id);
    if (!r.manager()) throw new NotFoundException("Policy", id);
    ObjectNode in = body != null && body.isObject() ? body.deepCopy() : Json.obj();
    boolean hasPolicyNo = in.has("policyNumber");
    boolean hasCardNo = in.has("cardNumber");
    String policyNo = hasPolicyNo ? number("policyNumber", in.remove("policyNumber")) : null;
    String cardNo = hasCardNo ? number("cardNumber", in.remove("cardNumber")) : null;
    ObjectNode values = VitaRecordsUseCase.clean(DETAIL_FIELDS, in, false);
    if (values.hasNonNull("cashlessCardDocumentId")) {
      UUID doc = UUID.fromString(values.get("cashlessCardDocumentId").asText());
      records.get("vita_document", r.owner(), doc).filter(d -> d.path("deletedAt").isNull())
          .orElseThrow(() -> new NotFoundException("Document", doc));
    }
    if (hasPolicyNo) values.put("policyNumberLast4", policyNo == null ? null : last4(policyNo));
    if (hasCardNo) values.put("cardNumberLast4", cardNo == null ? null : last4(cardNo));
    Optional<ObjectNode> existing = detailRow(r.owner(), id);
    UUID detailId;
    if (existing.isEmpty()) {
      values.put("policyId", id.toString());
      detailId = UUID.fromString(records.insert(DETAIL_TABLE, r.owner(), values).path("id").asText());
    } else {
      detailId = UUID.fromString(existing.get().path("id").asText());
      if (!values.isEmpty()) records.update(DETAIL_TABLE, r.owner(), detailId, values);
    }
    if (hasPolicyNo) {
      records.writeSecret(DETAIL_TABLE, r.owner(), detailId, "policyNumberEnc", policyNo == null ? null : crypto.encryptString(policyNo));
    }
    if (hasCardNo) {
      records.writeSecret(DETAIL_TABLE, r.owner(), detailId, "cardNumberEnc", cardNo == null ? null : crypto.encryptString(cardNo));
    }
    audit.record(r.owner(), ActorType.USER, caller, "vita.policy.detail_updated", id.toString(), null);
    return detail(caller, id);
  }

  // ── Claims ──────────────────────────────────────────────────────────────────

  public List<ObjectNode> claims(UUID caller, UUID personId) {
    PersonScope p = scopes.person(caller, personId, VitaSection.INSURANCE, false);
    Map<String, ObjectNode> byId = new HashMap<>();
    healthPolicies(p.owner()).forEach(r -> byId.put(r.path("id").asText(), r));
    return records.list(CLAIM_TABLE, p.owner(), VitaRecordsUseCase.livePerson(p.personId())).stream()
        .sorted(java.util.Comparator.comparing((ObjectNode c) -> c.path("admittedOn").asText("")).reversed())
        .map(c -> claimView(c, byId.get(c.path("policyId").asText()))).toList();
  }

  @Transactional
  public ObjectNode addClaim(UUID caller, UUID personId, JsonNode body) {
    PersonScope p = scopes.person(caller, personId, VitaSection.INSURANCE, true).requireOpen();
    if (records.list(CLAIM_TABLE, p.owner(), VitaRecordsUseCase.livePerson(p.personId())).size() >= MAX_CLAIMS) {
      throw new ValidationException("A record can hold at most " + MAX_CLAIMS + " claims.");
    }
    ObjectNode in = body != null && body.isObject() ? body.deepCopy() : Json.obj();
    if (!in.hasNonNull("policyId")) throw new ValidationException("policyId is needed.");
    ObjectNode policy = coveringPolicy(p, uuid("policyId", in.remove("policyId")));
    ObjectNode values = VitaRecordsUseCase.clean(CLAIM_FIELDS, in, true);
    forms.checkRefs(p.owner(), CLAIM_FIELDS, values);
    checkClaim(values, null);
    values.put("policyId", policy.path("id").asText());
    values.put("personId", p.personId().toString());
    values.put("source", "manual");
    values.put("createdByUserId", p.caller().toString());
    if (!values.hasNonNull("status")) values.put("status", "draft");
    ObjectNode row = records.insert(CLAIM_TABLE, p.owner(), values);
    audit.record(p.owner(), ActorType.USER, caller, "vita.claim.added", row.path("id").asText(), null);
    return claimView(row, policy);
  }

  /** The manager corrects any claim; a helper with Edit corrects the claims they added. */
  @Transactional
  public ObjectNode updateClaim(UUID caller, UUID personId, UUID id, JsonNode body) {
    PersonScope p = scopes.person(caller, personId, VitaSection.INSURANCE, true).requireOpen();
    ObjectNode current = claimRow(p, id);
    VitaRecordsUseCase.requireOwnEntry(p, current, "Claim", id);
    ObjectNode in = body != null && body.isObject() ? body.deepCopy() : Json.obj();
    ObjectNode policy = in.hasNonNull("policyId") ? coveringPolicy(p, uuid("policyId", in.remove("policyId")))
        : coveringPolicy(p, UUID.fromString(current.path("policyId").asText()));
    in.remove("policyId");
    ObjectNode values = VitaRecordsUseCase.clean(CLAIM_FIELDS, in, false);
    forms.checkRefs(p.owner(), CLAIM_FIELDS, values);
    checkClaim(values, current);
    values.put("policyId", policy.path("id").asText());
    ObjectNode row = records.update(CLAIM_TABLE, p.owner(), id, values).orElseThrow(() -> new NotFoundException("Claim", id));
    audit.record(p.owner(), ActorType.USER, caller, "vita.claim.updated", id.toString(), null);
    return claimView(row, policy);
  }

  @Transactional
  public void deleteClaim(UUID caller, UUID personId, UUID id) {
    PersonScope p = scopes.person(caller, personId, VitaSection.INSURANCE, true).requireOpen().requireManager();
    forms.removeRow(p, CLAIM_TABLE, "Claim", id);
    audit.record(p.owner(), ActorType.USER, caller, "vita.claim.removed", id.toString(), null);
  }

  // ── For Kai and the jobs ────────────────────────────────────────────────────

  /**
   * "Used this policy year" for each of the owner's health policies that has a renewal date: the
   * paid amount of settled claims whose stay began in the year ending on that date. Kai's
   * Protection view reads these totals and nothing else of a claim.
   */
  public Map<String, BigDecimal> usedThisYear(UUID owner, List<? extends JsonNode> policyRows) {
    Map<String, PolicyYear> years = new HashMap<>();
    for (JsonNode r : policyRows) {
      if (!PolicyYear.healthKind(r.path("kind").asText())) continue;
      PolicyYear y = PolicyYear.endingOn(date(r, "renewalDate"));
      if (y != null) years.put(r.path("id").asText(), y);
    }
    Map<String, BigDecimal> used = new LinkedHashMap<>();
    years.keySet().forEach(id -> used.put(id, BigDecimal.ZERO));
    if (years.isEmpty()) return used;
    Map<String, Object> where = new LinkedHashMap<>();
    where.put("status", "settled");
    where.put("deletedAt", null);
    for (ObjectNode c : records.list(CLAIM_TABLE, owner, where)) {
      String pid = c.path("policyId").asText();
      PolicyYear y = years.get(pid);
      if (y == null || !y.contains(date(c, "admittedOn")) || !c.hasNonNull("amountPaid")) continue;
      used.merge(pid, c.get("amountPaid").decimalValue(), BigDecimal::add);
    }
    return used;
  }

  /** The nightly run: keeps one renewal reminder per health policy. Returns how many were added. */
  public int renewals(UUID owner, ReminderSettings rs, Instant now) {
    int added = 0;
    for (ObjectNode row : healthPolicies(owner)) {
      if (renewal(owner, row, rs, now)) added++;
    }
    return added;
  }

  /**
   * The policy's renewal reminder (spec 5.2): {@code reminder.lead_days.policy_renewal} days
   * before the renewal date, at nine in the morning in India, for the policy holder. A renewal
   * date that moved moves it; one that passed or went away cancels it. Returns true when added.
   */
  boolean renewal(UUID owner, JsonNode row, ReminderSettings rs, Instant now) {
    UUID policyId = UUID.fromString(row.path("id").asText());
    LocalDate renews = date(row, "renewalDate");
    LocalDate today = LocalDate.ofInstant(now, VitaRecordsUseCase.ZONE);
    List<ObjectNode> mine = reminders(owner, policyId);
    ObjectNode scheduled = mine.stream().filter(r -> "scheduled".equals(r.path("status").asText())).findFirst().orElse(null);
    if (renews == null || renews.isBefore(today) || !PolicyYear.healthKind(row.path("kind").asText())) {
      if (scheduled != null) {
        records.update("vita_reminder", owner, UUID.fromString(scheduled.path("id").asText()), Json.obj().put("status", "cancelled"));
      }
      return false;
    }
    Instant due = reminderOn(renews, rs).atTime(REMINDER_AT).atZone(VitaRecordsUseCase.ZONE).toInstant();
    if (due.isBefore(now)) due = now;
    String holder = row.path("personId").asText();
    if (scheduled != null) {
      ObjectNode change = Json.obj();
      if (!due.equals(instant(scheduled.path("dueAt").asText()))) change.put("dueAt", due.toString());
      if (!holder.equals(scheduled.path("personId").asText())) change.put("personId", holder);
      if (!change.isEmpty()) records.update("vita_reminder", owner, UUID.fromString(scheduled.path("id").asText()), change);
      return false;
    }
    // One reminder per renewal: a sent or dismissed one for this year's date stays the only one.
    Instant yearStart = PolicyYear.endingOn(renews).from().atStartOfDay(VitaRecordsUseCase.ZONE).toInstant();
    boolean done = mine.stream().anyMatch(r -> !"cancelled".equals(r.path("status").asText())
        && Optional.ofNullable(instant(r.path("dueAt").asText())).map(at -> at.isAfter(yearStart)).orElse(false));
    if (done) return false;
    records.insert("vita_reminder", owner, Json.obj().put("personId", holder).put("kind", "policy_renewal")
        .put("refId", policyId.toString()).put("dueAt", due.toString()).put("status", "scheduled").put("source", "manual"));
    return true;
  }

  /** A health policy's renewal date, for the reminder's wording. */
  public Optional<LocalDate> renewsOn(UUID owner, UUID policyId) {
    return policies.get(POLICY_TABLE, owner, policyId).map(r -> date(r, "renewalDate"));
  }

  /** The day the renewal reminder is for. */
  static LocalDate reminderOn(LocalDate renews, ReminderSettings rs) {
    return renews.minusDays(rs.leadDays().getOrDefault("policy_renewal", 30));
  }

  // ── Views ───────────────────────────────────────────────────────────────────

  private List<ObjectNode> views(UUID owner, List<ObjectNode> rows, boolean manager) {
    if (rows.isEmpty()) return List.of();
    Map<String, BigDecimal> used = manager ? usedThisYear(owner, rows) : Map.of();
    ReminderSettings rs = ReminderSettings.of(config, owner);
    Map<String, ObjectNode> details = new HashMap<>();
    records.list(DETAIL_TABLE, owner, Map.of()).forEach(d -> details.put(d.path("policyId").asText(), d));
    Map<String, Integer> claims = new HashMap<>();
    if (manager) {
      Map<String, Object> live = new LinkedHashMap<>();
      live.put("deletedAt", null);
      records.list(CLAIM_TABLE, owner, live).forEach(c -> claims.merge(c.path("policyId").asText(), 1, Integer::sum));
    }
    List<ObjectNode> out = new ArrayList<>();
    for (ObjectNode r : rows) {
      String id = r.path("id").asText();
      ObjectNode v = Json.obj().put("id", id).put("personId", r.path("personId").asText()).put("kind", r.path("kind").asText());
      for (String f : List.of("providerName", "sumAssured", "deductible", "premium", "premiumFrequency",
          "renewalDate", "policyEndDate", "employerProvided")) {
        v.set(f, r.path(f).isMissingNode() ? Json.mapper().nullNode() : r.get(f));
      }
      ArrayNode covered = v.putArray("coveredPersonIds");
      List<String> ids = covered(r);
      ids.forEach(covered::add);
      v.put("floater", ids.size() > 1);
      PolicyYear y = PolicyYear.endingOn(date(r, "renewalDate"));
      if (y == null) {
        v.putNull("policyYear");
      } else {
        v.putObject("policyYear").put("from", y.from().toString()).put("to", y.to().toString());
      }
      if (manager && y != null) {
        BigDecimal u = used.getOrDefault(id, BigDecimal.ZERO);
        v.put("usedThisYear", u);
        v.put("leftThisYear", PolicyYear.left(r.path("sumAssured").decimalValue(), u));
      } else {
        v.putNull("usedThisYear");
        v.putNull("leftThisYear");
      }
      LocalDate renews = date(r, "renewalDate");
      v.put("renewalReminderOn", renews == null ? null : reminderOn(renews, rs).toString());
      v.set("detail", detailView(details.get(id), UUID.fromString(id)));
      if (manager) v.put("claimCount", claims.getOrDefault(id, 0));
      v.put("shared", !manager);
      out.add(v);
    }
    return out;
  }

  private static ObjectNode detailView(ObjectNode d, UUID policyId) {
    ObjectNode v = Json.obj().put("policyId", policyId.toString());
    v.put("policyNumberMasked", masked(d == null ? null : d.path("policyNumberLast4").asText(null)));
    v.put("cardNumberMasked", masked(d == null ? null : d.path("cardNumberLast4").asText(null)));
    for (String f : List.of("tpaName", "cashlessCardDocumentId", "roomRentLimit", "copayPct", "networkHospitalsNote")) {
      v.set(f, d == null || d.path(f).isMissingNode() ? Json.mapper().nullNode() : d.get(f));
    }
    v.set("waitingPeriods", d == null || !d.path("waitingPeriods").isArray() ? Json.mapper().createArrayNode() : d.get("waitingPeriods"));
    return v;
  }

  private static ObjectNode claimView(ObjectNode c, ObjectNode policy) {
    ObjectNode v = c.deepCopy();
    v.remove("userId");
    v.remove("deletedAt");
    BigDecimal claimed = c.hasNonNull("amountClaimed") ? c.get("amountClaimed").decimalValue() : null;
    BigDecimal paid = c.hasNonNull("amountPaid") ? c.get("amountPaid").decimalValue() : null;
    BigDecimal oop = PolicyYear.outOfPocket(c.path("status").asText(), claimed, paid);
    if (oop == null) v.putNull("outOfPocket"); else v.put("outOfPocket", oop);
    if (policy != null) {
      v.putObject("policy").put("kind", policy.path("kind").asText())
          .put("providerName", policy.path("providerName").asText(null));
      PolicyYear y = PolicyYear.endingOn(date(policy, "renewalDate"));
      LocalDate stay = date(c, "admittedOn");
      if (y != null && stay != null) {
        // A stay after this year's renewal date falls in the next policy year.
        v.put("policyYearTo", PolicyYear.containing(y.to(), stay).to().toString());
      }
    }
    return v;
  }

  static String masked(String last4) {
    return last4 == null || last4.isBlank() ? null : "•••• " + last4;
  }

  // ── Helpers ─────────────────────────────────────────────────────────────────

  private record Reached(UUID owner, ObjectNode row, boolean manager) {}

  /**
   * A health policy by id: the caller's own (as manager), or another manager's that covers a
   * person whose insurance section is shared with the caller. Everything else is not found.
   */
  private Reached reach(UUID caller, UUID id) {
    UUID own = scopes.own(caller).ownerUserId();
    Optional<ObjectNode> mine = policies.get(POLICY_TABLE, own, id).filter(r -> PolicyYear.healthKind(r.path("kind").asText()));
    if (mine.isPresent()) return new Reached(own, mine.get(), true);
    for (UUID owner : scopes.reachableOwners(caller)) {
      if (owner.equals(own)) continue;
      Optional<ObjectNode> row = policies.get(POLICY_TABLE, owner, id).filter(r -> PolicyYear.healthKind(r.path("kind").asText()));
      if (row.isEmpty()) continue;
      for (String pid : covered(row.get())) {
        try {
          scopes.person(caller, UUID.fromString(pid), VitaSection.INSURANCE, false);
          return new Reached(owner, row.get(), false);
        } catch (NotFoundException ignored) {
          // not this person
        }
      }
    }
    throw new NotFoundException("Policy", id);
  }

  private List<ObjectNode> healthPolicies(UUID owner) {
    return policies.list(POLICY_TABLE, owner, Map.of()).stream()
        .filter(r -> PolicyYear.healthKind(r.path("kind").asText())).toList();
  }

  /** The policy, which must be one of the owner's health policies covering this person. */
  private ObjectNode coveringPolicy(PersonScope p, UUID policyId) {
    return policies.get(POLICY_TABLE, p.owner(), policyId)
        .filter(r -> PolicyYear.healthKind(r.path("kind").asText()))
        .filter(r -> covered(r).contains(p.personId().toString()))
        .orElseThrow(() -> new NotFoundException("Policy", policyId));
  }

  private ObjectNode claimRow(PersonScope p, UUID id) {
    return records.get(CLAIM_TABLE, p.owner(), id)
        .filter(r -> p.personId().toString().equals(r.path("personId").asText()))
        .filter(r -> r.path("deletedAt").isNull())
        .orElseThrow(() -> new NotFoundException("Claim", id));
  }

  private Optional<ObjectNode> detailRow(UUID owner, UUID policyId) {
    return records.list(DETAIL_TABLE, owner, Map.of("policyId", policyId)).stream().findFirst();
  }

  private List<ObjectNode> reminders(UUID owner, UUID policyId) {
    Map<String, Object> where = new LinkedHashMap<>();
    where.put("kind", "policy_renewal");
    where.put("refId", policyId);
    where.put("deletedAt", null);
    return records.list("vita_reminder", owner, where);
  }

  private void cancelRenewal(UUID owner, UUID policyId) {
    reminders(owner, policyId).stream().filter(r -> "scheduled".equals(r.path("status").asText()))
        .forEach(r -> records.update("vita_reminder", owner, UUID.fromString(r.path("id").asText()),
            Json.obj().put("status", "cancelled")));
  }

  /** Whom the policy covers: the listed people, else its holder (as Kai reads it). */
  static List<String> covered(JsonNode r) {
    List<String> ids = new ArrayList<>();
    r.path("coveredPersonIds").forEach(n -> ids.add(n.asText()));
    if (ids.isEmpty() && r.hasNonNull("personId")) ids.add(r.get("personId").asText());
    return ids;
  }

  /** The covered people: each one of the owner's own; the holder when none is named. */
  private ArrayNode coveredIds(UUID owner, JsonNode in, UUID holder) {
    ArrayNode out = Json.mapper().createArrayNode();
    if (in == null || in.isNull() || (in.isArray() && in.isEmpty())) {
      out.add(holder.toString());
      return out;
    }
    if (!in.isArray() || in.size() > 20) throw new ValidationException("coveredPersonIds must be a list of at most 20 people.");
    Set<String> seen = new LinkedHashSet<>();
    for (JsonNode n : in) {
      UUID id = uuid("coveredPersonIds", n);
      people.person(owner, id).filter(r -> !"huf".equals(r.kind()))
          .orElseThrow(() -> new NotFoundException("Person", id));
      if (seen.add(id.toString())) out.add(id.toString());
    }
    return out;
  }

  /** A sum insured is needed, except for a government scheme, which may have none printed. */
  private static void sumNeeded(ObjectNode values, JsonNode current) {
    String kind = values.hasNonNull("kind") ? values.get("kind").asText() : current == null ? null : current.path("kind").asText();
    boolean sumSent = values.has("sumAssured");
    if (sumSent && values.get("sumAssured").isNull()) values.put("sumAssured", BigDecimal.ZERO);
    if (current == null && !sumSent) values.put("sumAssured", BigDecimal.ZERO);
    BigDecimal sum = values.hasNonNull("sumAssured") ? values.get("sumAssured").decimalValue()
        : current == null ? BigDecimal.ZERO : current.path("sumAssured").decimalValue();
    if (!"government_scheme".equals(kind) && sum.signum() <= 0) throw new ValidationException("sumAssured is needed.");
  }

  private static void checkClaim(ObjectNode values, JsonNode current) {
    LocalDate in = values.has("admittedOn") ? date(values, "admittedOn") : current == null ? null : date(current, "admittedOn");
    LocalDate out = values.has("dischargedOn") ? date(values, "dischargedOn") : current == null ? null : date(current, "dischargedOn");
    if (in != null && out != null && out.isBefore(in)) throw new ValidationException("dischargedOn cannot be before admittedOn.");
    String status = values.hasNonNull("status") ? values.get("status").asText() : current == null ? "draft" : current.path("status").asText();
    boolean paidSet = values.has("amountPaid") ? values.hasNonNull("amountPaid") : current != null && current.hasNonNull("amountPaid");
    if ("settled".equals(status) && !paidSet) throw new ValidationException("A settled claim needs amountPaid.");
  }

  private static String number(String name, JsonNode v) {
    if (v == null || v.isNull() || (v.isTextual() && v.asText().isBlank())) return null;
    String s = v.isNumber() ? v.asText() : v.isTextual() ? v.asText().strip() : null;
    if (s == null || !NUMBER.matcher(s).matches() || s.chars().filter(Character::isLetterOrDigit).count() < 4) {
      throw new ValidationException(name + " must be the number as printed: letters, digits, spaces, / and -.");
    }
    return s;
  }

  private static String last4(String s) {
    String alnum = s.replaceAll("[^A-Za-z0-9]", "");
    return alnum.substring(alnum.length() - 4);
  }

  private static UUID uuid(String name, JsonNode v) {
    try {
      return UUID.fromString(v.asText());
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new ValidationException(name + " must be an id.");
    }
  }

  private static Instant instant(String s) {
    if (s == null || s.isBlank()) return null;
    try {
      return java.time.OffsetDateTime.parse(s).toInstant();
    } catch (RuntimeException e) {
      try {
        return Instant.parse(s);
      } catch (RuntimeException e2) {
        return null;
      }
    }
  }

  private static LocalDate date(JsonNode r, String field) {
    String s = r.path(field).asText(null);
    if (s == null || s.isBlank()) return null;
    return LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s);
  }

  private static Map<String, VitaField> fields(VitaField... fs) {
    Map<String, VitaField> m = new LinkedHashMap<>();
    for (VitaField f : fs) m.put(f.name(), f);
    return java.util.Collections.unmodifiableMap(m);
  }
}
