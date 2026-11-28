package com.jizza.platform.usecase.vita.labs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.util.Json;
import com.jizza.platform.usecase.vita.home.VitaHomeUseCase;
import com.jizza.platform.usecase.vita.ranges.VitaTargetService;
import com.jizza.platform.usecase.vita.records.VitaRecordsUseCase;
import com.jizza.platform.usecase.vita.sharing.VitaScopeResolver.PersonScope;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Lab values and checks on Home and on condition pages (spec 5.6): for each key test of the
 * template, its trend across confirmed reports with the lab flag and the target status kept apart;
 * and each check with its last date and whether it is due.
 */
@Component
public class VitaLabHomeCards implements VitaHomeUseCase.HomeExtras {

  /** The key tests each template shows (spec 2.5). */
  static final List<String> DIABETES = List.of("hba1c", "fasting_glucose", "pp_glucose", "creatinine", "egfr", "uacr",
      "total_cholesterol", "ldl", "hdl", "triglycerides");
  static final List<String> HYPERTENSION = List.of("creatinine", "egfr", "total_cholesterol", "ldl", "hdl", "triglycerides");
  static final List<String> DIABETES_CHECKS = List.of("hba1c", "eye", "foot", "urine_albumin", "kidney_function", "lipids");
  static final List<String> HYPERTENSION_CHECKS = List.of("bp_checks", "kidney_function", "lipids");

  private final VitaLabUseCase labs;
  private final VitaCheckUseCase checks;
  private final VitaTargetService targets;
  private final VitaLabCatalog catalog;

  public VitaLabHomeCards(VitaLabUseCase labs, VitaCheckUseCase checks, VitaTargetService targets, VitaLabCatalog catalog) {
    this.labs = labs;
    this.checks = checks;
    this.targets = targets;
    this.catalog = catalog;
  }

  @Override
  public void addToHome(PersonScope p, ObjectNode home) {
    if (!p.canView(com.jizza.platform.usecase.vita.sharing.VitaSection.LABS)) return;
    var reports = labs.reports(p).stream().filter(r -> "confirmed".equals(r.path("status").asText()))
        .filter(r -> VitaLabUseCase.date(r) != null)
        .max(java.util.Comparator.comparing(VitaLabUseCase::date));
    if (reports.isEmpty()) return;
    ObjectNode r = reports.get();
    List<ObjectNode> rows = labs.results(p, UUID.fromString(r.path("id").asText()));
    home.set("lastLabReport", Json.obj().put("id", r.path("id").asText())
        .put("date", VitaLabUseCase.date(r).toString()).put("labName", r.path("labName").asText(null))
        .put("rows", rows.size())
        .put("labFlagged", rows.stream().filter(x -> Set.of("low", "high", "critical").contains(x.path("flag").asText())).count()));
  }

  @Override
  public void addToSummary(PersonScope p, ObjectNode condition, ObjectNode summary) {
    String template = condition.path("template").asText("generic");
    List<String> tests = switch (template) {
      case "diabetes_t2" -> DIABETES;
      case "hypertension" -> HYPERTENSION;
      default -> {
        List<String> chosen = new java.util.ArrayList<>();
        for (JsonNode m : condition.path("measures")) {
          if (catalog.known(m.asText())) chosen.add(m.asText());
        }
        yield chosen;
      }
    };
    VitaTargetService.Context ctx = targets.context(p);
    VitaLabUseCase.Formulas formulas = labs.formulas(p);
    if (!p.canView(com.jizza.platform.usecase.vita.sharing.VitaSection.LABS)) return;
    ArrayNode out = summary.putArray("labResults");
    for (String code : tests) {
      var test = catalog.normaliser().byCode(code).orElse(null);
      if (test == null) continue;
      ObjectNode t = labs.trend(p, ctx, test, formulas);
      if (t.path("count").asInt() > 0) out.add(t);
    }
    // The text Home showed before any report stays until there is a result to show.
    if (!out.isEmpty()) summary.remove("labs");

    List<String> names = switch (template) {
      case "diabetes_t2" -> DIABETES_CHECKS;
      case "hypertension" -> HYPERTENSION_CHECKS;
      default -> List.of();
    };
    if (!names.isEmpty() && p.canView(com.jizza.platform.usecase.vita.sharing.VitaSection.VISITS)) {
      UUID conditionId = condition.hasNonNull("id") ? UUID.fromString(condition.get("id").asText()) : null;
      summary.set("checks", checks.statuses(p, ctx, "clinical.intervals." + template + ".", names, conditionId,
          LocalDate.now(VitaRecordsUseCase.ZONE)));
    }
  }
}
