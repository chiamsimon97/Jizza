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
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Insurance policies, the protection calculator (5.9) and wills. */
@Service
public class FinProtectionUseCase {

  private final FinanceRecords records;
  private final FinRecordsUseCase forms;
  private final HouseholdLoader loader;
  private final FinScopeResolver scopes;

  public FinProtectionUseCase(FinanceRecords records, FinRecordsUseCase forms,
                              HouseholdLoader loader, FinScopeResolver scopes) {
    this.records = records;
    this.forms = forms;
    this.loader = loader;
    this.scopes = scopes;
  }

  public List<ObjectNode> policies(UUID caller) {
    return forms.list(caller, FinTable.POLICY, Map.of());
  }

  public ObjectNode addPolicy(UUID caller, JsonNode body) {
    return forms.create(caller, FinTable.POLICY, body, null);
  }

  public ObjectNode updatePolicy(UUID caller, UUID id, JsonNode body) {
    return forms.update(caller, FinTable.POLICY, id, body);
  }

  public void deletePolicy(UUID caller, UUID id) {
    forms.delete(caller, FinTable.POLICY, id);
  }

  /**
   * @param known false when the user skipped insurance and recorded none: gaps are then unknown
   */
  public record ProtectionView(LocalDate asOf, boolean known, Protection protection,
                               Map<String, String> names) {}

  public ProtectionView protection(UUID caller) {
    UUID owner = scopes.own(caller).ownerUserId();
    var loaded = loader.load(owner);
    Household h = loaded.household();
    var cf = CashFlowService.compute(h);
    Protection p = ProtectionCalculator.compute(h, cf, MoneyHealthService.liquidEmergency(h),
        loaded.config(), Map.of());
    Map<String, String> names = new java.util.LinkedHashMap<>();
    h.people().forEach(person -> names.put(person.id(), person.name()));
    return new ProtectionView(h.asOf(), h.knows(Household.INSURANCE), p, names);
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
