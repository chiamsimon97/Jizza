package com.jizza.platform.usecase.finance.protection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.finance.FinanceRecords;
import com.jizza.engine.api.util.Json;
import com.jizza.platform.usecase.finance.cashflow.CashFlowService;
import com.jizza.platform.usecase.finance.health.HouseholdLoader;
import com.jizza.platform.usecase.finance.health.MoneyHealthService;
import com.jizza.platform.usecase.finance.model.Household;
import com.jizza.platform.usecase.finance.protection.ProtectionCalculator.Protection;
import com.jizza.platform.usecase.finance.records.FinRecordsUseCase;
import com.jizza.platform.usecase.finance.records.FinTable;
import com.jizza.platform.usecase.finance.sharing.FinScopeResolver;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.platform.usecase.vita.insurance.PolicyYear;
import com.jizza.platform.usecase.vita.insurance.VitaInsuranceUseCase;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Insurance policies, the protection calculator (5.9) and wills. */
@Service
public class FinProtectionUseCase {

  private final FinanceRecords records;
  private final FinRecordsUseCase forms;
  private final HouseholdLoader loader;
  private final FinScopeResolver scopes;
  private final ObjectProvider<VitaInsuranceUseCase> vita;
  private final boolean healthOn;

  /** Said when Kai is asked to change health cover while Vita owns it (Vita spec 4.3). */
  public static final String KEPT_IN_VITA = "Health cover is kept in Vita while the Health tab is on. "
      + "Open Vita's Insurance view to add or change it.";

  public FinProtectionUseCase(FinanceRecords records, FinRecordsUseCase forms,
                              HouseholdLoader loader, FinScopeResolver scopes,
                              ObjectProvider<VitaInsuranceUseCase> vita,
                              @Value("${jizza.health.enabled:false}") boolean healthOn) {
    this.records = records;
    this.forms = forms;
    this.loader = loader;
    this.scopes = scopes;
    this.vita = vita;
    this.healthOn = healthOn;
  }

  /** Whether Vita owns the health kinds now: only while the health switch is on. */
  public boolean healthInVita() {
    return healthOn;
  }

  /**
   * Every policy. Health cover carries "used this policy year" from Vita's claims and, while the
   * health switch is on, {@code editInVita}: Kai shows it but no longer changes it.
   */
  public List<ObjectNode> policies(UUID caller) {
    UUID owner = scopes.own(caller).ownerUserId();
    List<ObjectNode> rows = forms.list(caller, FinTable.POLICY, Map.of());
    Map<String, java.math.BigDecimal> used = used(owner, rows);
    for (ObjectNode r : rows) {
      String id = r.path("id").asText();
      if (used.containsKey(id)) r.put("usedThisYear", used.get(id)); else r.putNull("usedThisYear");
      r.put("editInVita", healthOn && PolicyYear.healthKind(r.path("kind").asText()));
    }
    return rows;
  }

  public ObjectNode addPolicy(UUID caller, JsonNode body) {
    refuseHealth(body == null ? null : body.path("kind").asText(null));
    return forms.create(caller, FinTable.POLICY, body, null);
  }

  public ObjectNode updatePolicy(UUID caller, UUID id, JsonNode body) {
    refuseHealth(forms.get(caller, FinTable.POLICY, id).path("kind").asText(null));
    if (body != null && body.hasNonNull("kind")) refuseHealth(body.get("kind").asText());
    return forms.update(caller, FinTable.POLICY, id, body);
  }

  public void deletePolicy(UUID caller, UUID id) {
    refuseHealth(forms.get(caller, FinTable.POLICY, id).path("kind").asText(null));
    forms.delete(caller, FinTable.POLICY, id);
  }

  /** While Vita owns health cover, Kai's forms leave those kinds alone (Vita spec 4.3). */
  public void refuseHealth(String kind) {
    if (healthOn && PolicyYear.healthKind(kind)) throw new ValidationException(KEPT_IN_VITA);
  }

  private Map<String, java.math.BigDecimal> used(UUID owner, List<? extends JsonNode> rows) {
    VitaInsuranceUseCase v = vita.getIfAvailable();
    return v == null ? Map.of() : v.usedThisYear(owner, rows);
  }

  /**
   * @param known false when the user skipped insurance and recorded none: gaps are then unknown
   * @param policies every policy once, a floater as one policy covering several people, with
   *     "used this policy year" for health cover that has a renewal date
   * @param healthInVita true while Vita owns health cover: Kai shows it and links there to edit
   */
  public record ProtectionView(LocalDate asOf, boolean known, Protection protection,
                               Map<String, String> names, List<ProtectionCalculator.PolicyLine> policies,
                               boolean healthInVita) {}

  public ProtectionView protection(UUID caller) {
    UUID owner = scopes.own(caller).ownerUserId();
    var loaded = loader.load(owner);
    Household h = loaded.household();
    var cf = CashFlowService.compute(h);
    Protection p = ProtectionCalculator.compute(h, cf, MoneyHealthService.liquidEmergency(h),
        loaded.config(), Map.of());
    Map<String, String> names = new java.util.LinkedHashMap<>();
    h.people().forEach(person -> names.put(person.id(), person.name()));
    List<ObjectNode> rows = records.list(FinTable.POLICY.table, owner, Map.of());
    var lines = ProtectionCalculator.policyLines(h, used(owner, rows));
    return new ProtectionView(h.asOf(), h.knows(Household.INSURANCE), p, names, lines, healthOn);
  }

  /** The will record for a person; an empty "none" record when there is nothing yet. */
  public ObjectNode estate(UUID caller, UUID personId) {
    UUID owner = scopes.own(caller).ownerUserId();
    forms.requirePerson(owner, personId);
    return records.list("fin_estate_record", owner, Map.of("personId", personId.toString())).stream()
        .findFirst().orElseGet(() -> Json.obj().put("personId", personId.toString())
            .put("willStatus", "none"));
  }

  public ObjectNode putEstate(UUID caller, UUID personId, JsonNode body) {
    UUID owner = scopes.own(caller).ownerUserId();
    forms.requirePerson(owner, personId);
    var existing = records.list("fin_estate_record", owner, Map.of("personId", personId.toString()));
    if (existing.isEmpty()) {
      return forms.create(caller, FinTable.ESTATE, body, Map.of("personId", personId));
    }
    return forms.update(caller, FinTable.ESTATE, UUID.fromString(existing.get(0).path("id").asText()), body);
  }
}
