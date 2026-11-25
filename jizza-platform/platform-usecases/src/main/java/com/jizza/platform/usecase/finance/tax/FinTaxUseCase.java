package com.jizza.platform.usecase.finance.tax;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.finance.FinanceRecords;
import com.jizza.engine.api.util.Json;
import com.jizza.platform.usecase.finance.config.FinConfigService;
import com.jizza.platform.usecase.finance.config.FinConfigService.ConfigItem;
import com.jizza.platform.usecase.finance.health.HouseholdLoader;
import com.jizza.platform.usecase.finance.model.Household.Person;
import com.jizza.platform.usecase.finance.model.Inr;
import com.jizza.platform.usecase.finance.records.FinRecordsUseCase;
import com.jizza.platform.usecase.finance.records.FinTable;
import com.jizza.platform.usecase.finance.sharing.FinScopeResolver;
import com.jizza.platform.usecase.finance.tax.TaxRegimeComparator.Comparison;
import com.jizza.platform.usecase.finance.tax.TaxRegimeComparator.Rules;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Tax screen (7.24) for one person: the old and new regimes compared from their income and the
 * deductions they entered, with every figure from {@code fin_config} and a "needs verification"
 * flag while any of them is unconfirmed. Realised gains and the exemption left arrive with
 * holdings in Phase 2.
 */
@Service
public class FinTaxUseCase {

  public static final List<String> KEYS = List.of("tax.new_regime", "tax.old_regime", "tax.cess");

  private final FinanceRecords records;
  private final FinRecordsUseCase forms;
  private final HouseholdLoader loader;
  private final FinConfigService config;
  private final FinScopeResolver scopes;
  private final AuditLog audit;

  public FinTaxUseCase(FinanceRecords records, FinRecordsUseCase forms, HouseholdLoader loader,
                       FinConfigService config, FinScopeResolver scopes, AuditLog audit) {
    this.records = records;
    this.forms = forms;
    this.loader = loader;
    this.config = config;
    this.scopes = scopes;
    this.audit = audit;
  }

  /**
   * @param profile what the person entered (deductions, rent, metro, chosen regime)
   * @param unverified the tax settings that still need verification
   */
  public record TaxView(String personId, String personName, String year, ObjectNode profile,
                        BigDecimal grossAnnual, Comparison comparison, List<String> unverified,
                        String gainsNote) {}

  /** The financial year (April to March) a date falls in: "2026-27". */
  public static String financialYear(LocalDate d) {
    int start = d.getMonthValue() >= 4 ? d.getYear() : d.getYear() - 1;
    return start + "-" + String.format("%02d", (start + 1) % 100);
  }

  public TaxView get(UUID caller, UUID personId, String year) {
    UUID owner = scopes.own(caller).ownerUserId();
    forms.requirePerson(owner, personId);
    String fy = year == null || year.isBlank() ? financialYear(HouseholdLoader.today()) : year;
    Map<String, ConfigItem> items = new HashMap<>();
    for (ConfigItem i : config.list(owner)) if (KEYS.contains(i.key())) items.put(i.key(), i);
    for (String k : KEYS) {
      if (!items.containsKey(k)) throw new IllegalStateException("Missing setting " + k);
      String has = items.get(k).value().path("financialYear").asText();
      if (!fy.equals(has)) {
        throw new ValidationException("Kai has tax figures for " + has + " only, not " + fy + ".");
      }
    }
    List<String> unverified = KEYS.stream().filter(k -> items.get(k).needsVerification()).toList();
    Rules rules = Rules.from(items.get("tax.new_regime").value(), items.get("tax.old_regime").value(),
        items.get("tax.cess").value(), !unverified.isEmpty());

    var loaded = loader.load(owner);
    Person person = loaded.household().people().stream().filter(p -> p.id().equals(personId.toString()))
        .findFirst().orElseThrow(() -> new NotFoundException("Person", personId));
    BigDecimal gross = null;
    BigDecimal basic = null;
    BigDecimal hra = null;
    boolean salaried = false;
    for (ObjectNode i : records.list("fin_income_source", owner, Map.of("personId", personId.toString()))) {
      BigDecimal g = HouseholdLoader.dec(i, "grossMonthly");
      if (g != null) gross = Inr.nz(gross).add(g.multiply(Inr.TWELVE));
      if (HouseholdLoader.dec(i, "basicMonthly") != null) basic = Inr.nz(basic).add(HouseholdLoader.dec(i, "basicMonthly").multiply(Inr.TWELVE));
      if (HouseholdLoader.dec(i, "hraMonthly") != null) hra = Inr.nz(hra).add(HouseholdLoader.dec(i, "hraMonthly").multiply(Inr.TWELVE));
      String kind = i.path("kind").asText("salary");
      if ("salary".equals(kind) || "pension".equals(kind)) salaried = true;
    }
    ObjectNode profile = profile(owner, personId, fy);
    Map<String, BigDecimal> deductions = new LinkedHashMap<>();
    JsonNode d = profile.path("deductions");
    for (String k : TaxRegimeComparator.DEDUCTION_KEYS) {
      if (d.hasNonNull(k)) deductions.put(k, d.get(k).decimalValue());
    }
    BigDecimal rent = HouseholdLoader.dec(profile, "rentPaidMonthly");
    Boolean metro = profile.hasNonNull("metro") ? profile.get("metro").asBoolean() : null;
    var input = new TaxRegimeComparator.Input(gross, salaried, person.ageOn(loaded.household().asOf()),
        d.path("parentsSenior").asBoolean(false), deductions, basic, hra,
        rent == null ? null : rent.multiply(Inr.TWELVE), metro);
    Comparison c = TaxRegimeComparator.compare(input, rules);
    return new TaxView(personId.toString(), person.name(), fy, profile, gross, c, unverified,
        "Realised gains, the exemption left this year and advance-tax dates come with holdings.");
  }

  /** For Kai's tool: one person, or the caller's own "Me" when none is named. */
  public TaxView forPerson(UUID caller, String personId) {
    UUID owner = scopes.own(caller).ownerUserId();
    UUID id = personId == null || personId.isBlank() ? forms.selfId(owner)
        : FinRecordsUseCase.uuidOf("personId", com.fasterxml.jackson.databind.node.TextNode.valueOf(personId));
    return get(caller, id, null);
  }

  /** Saves the person's deductions, rent and chosen regime for a year, and returns the comparison. */
  @Transactional
  public TaxView put(UUID caller, UUID personId, JsonNode body) {
    UUID owner = scopes.own(caller).ownerUserId();
    forms.requirePerson(owner, personId);
    if (body == null || !body.isObject()) throw new ValidationException("Send the tax details as an object.");
    String fy = body.hasNonNull("year") ? body.get("year").asText() : financialYear(HouseholdLoader.today());
    if (!fy.matches("\\d{4}-\\d{2}")) throw new ValidationException("year must look like 2026-27.");
    ObjectNode fields = Json.obj();
    for (String f : List.of("regime", "rentPaidMonthly", "metro")) if (body.has(f)) fields.set(f, body.get(f));
    ObjectNode v = forms.clean(owner, FinTable.TAX_PROFILE, fields, false);
    if (body.has("deductions")) v.set("deductions", deductions(body.get("deductions")));
    ObjectNode current = profile(owner, personId, fy);
    if (current.hasNonNull("id")) {
      records.update("fin_tax_profile", owner, UUID.fromString(current.get("id").asText()), v);
    } else {
      v.put("personId", personId.toString()).put("taxYear", fy);
      records.insert("fin_tax_profile", owner, v);
    }
    audit.record(owner, ActorType.USER, caller, "finance.tax.updated", personId.toString(), null);
    TaxView view = get(caller, personId, fy);
    ObjectNode row = profile(owner, personId, fy);
    records.update("fin_tax_profile", owner, UUID.fromString(row.get("id").asText()),
        Json.obj().set("regimeCompare", Json.toNode(view.comparison())));
    return view;
  }

  private ObjectNode profile(UUID owner, UUID personId, String fy) {
    List<ObjectNode> rows = records.list("fin_tax_profile", owner,
        Map.of("personId", personId.toString(), "taxYear", fy));
    if (!rows.isEmpty()) return rows.get(0);
    ObjectNode empty = Json.obj().put("personId", personId.toString()).put("taxYear", fy);
    empty.set("deductions", Json.obj());
    return empty;
  }

  static ObjectNode deductions(JsonNode d) {
    if (d == null || !d.isObject()) throw new ValidationException("deductions must be an object.");
    ObjectNode out = Json.obj();
    Iterator<Map.Entry<String, JsonNode>> it = d.fields();
    while (it.hasNext()) {
      var e = it.next();
      String k = e.getKey();
      JsonNode v = e.getValue();
      if ("parentsSenior".equals(k)) {
        if (!v.isBoolean()) throw new ValidationException("parentsSenior must be true or false.");
        out.put(k, v.asBoolean());
        continue;
      }
      if (!TaxRegimeComparator.DEDUCTION_KEYS.contains(k)) {
        throw new ValidationException(k + " is not a deduction Kai knows. Use one of "
            + String.join(", ", TaxRegimeComparator.DEDUCTION_KEYS) + ".");
      }
      if (v == null || v.isNull()) continue;
      BigDecimal amount;
      try {
        amount = v.isNumber() ? v.decimalValue() : new BigDecimal(v.asText().replace(",", "").trim());
      } catch (NumberFormatException ex) {
        throw new ValidationException(k + " must be an amount in rupees.");
      }
      if (amount.signum() < 0 || amount.compareTo(new BigDecimal("1000000000")) > 0) {
        throw new ValidationException(k + " must be an amount in rupees.");
      }
      out.put(k, amount);
    }
    return out;
  }
}
