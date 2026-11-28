package com.jizza.platform.usecase.vita.labs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.vita.VitaRecords;
import com.jizza.platform.usecase.vita.config.VitaConfigService;
import com.jizza.platform.usecase.vita.config.VitaConfigService.ConfigItem;
import com.jizza.platform.usecase.vita.ranges.RangeSet;
import com.jizza.platform.usecase.vita.ranges.TargetEvaluator;
import com.jizza.platform.usecase.vita.ranges.VitaTargetService;
import com.jizza.platform.usecase.vita.records.VitaRecordsUseCase;
import com.jizza.platform.usecase.vita.records.VitaTable;
import com.jizza.platform.usecase.vita.sharing.VitaScopeResolver;
import com.jizza.platform.usecase.vita.sharing.VitaScopeResolver.PersonScope;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Period;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Checks (spec 4.2, 2.5): the ones the user records (eye, foot, dental, ...) and, for checks that
 * are lab tests, "last done" from confirmed lab results. A check is due only from a date the doctor
 * gave or from an interval that is set in config; with neither it reads "not set: ask your doctor".
 * Vita works out no interval of its own.
 */
@Service
public class VitaCheckUseCase {

  /** Which lab tests and which recorded check kinds count as each condition check. */
  record Sources(List<String> labTests, List<String> kinds) {}

  static final Map<String, Sources> SOURCES = Map.of(
      "hba1c", new Sources(List.of("hba1c"), List.of("hba1c")),
      "eye", new Sources(List.of(), List.of("eye")),
      "foot", new Sources(List.of(), List.of("foot")),
      "dental", new Sources(List.of(), List.of("dental")),
      "urine_albumin", new Sources(List.of("uacr"), List.of()),
      "kidney_function", new Sources(List.of("creatinine", "egfr"), List.of("kidney")),
      "lipids", new Sources(List.of("total_cholesterol", "ldl", "hdl", "triglycerides"), List.of("lipids")),
      "bp_checks", new Sources(List.of(), List.of()));

  static final String DUE = "due";
  static final String UPCOMING = "upcoming";
  static final String NO_RECORD = "no_record";

  private final VitaRecords records;
  private final VitaScopeResolver scopes;
  private final VitaRecordsUseCase forms;
  private final VitaConfigService config;
  private final VitaLabUseCase labs;

  public VitaCheckUseCase(VitaRecords records, VitaScopeResolver scopes, VitaRecordsUseCase forms,
                          VitaConfigService config, VitaLabUseCase labs) {
    this.records = records;
    this.scopes = scopes;
    this.forms = forms;
    this.config = config;
    this.labs = labs;
  }

  // ── The form ───────────────────────────────────────────────────────────────

  public List<ObjectNode> list(UUID caller, UUID personId) {
    return forms.list(caller, personId, VitaTable.CHECK);
  }

  @Transactional
  public ObjectNode create(UUID caller, UUID personId, JsonNode body) {
    validate(body, null);
    return forms.create(caller, personId, VitaTable.CHECK, body, "manual");
  }

  @Transactional
  public ObjectNode update(UUID caller, UUID personId, UUID id, JsonNode body) {
    ObjectNode current = forms.get(caller, personId, VitaTable.CHECK, id);
    validate(body, current);
    return forms.update(caller, personId, VitaTable.CHECK, id, body);
  }

  @Transactional
  public void delete(UUID caller, UUID personId, UUID id) {
    forms.delete(caller, personId, VitaTable.CHECK, id);
  }

  /** A check needs a date (done or due), and a custom check needs a name. */
  static void validate(JsonNode body, ObjectNode current) {
    if (body == null || !body.isObject()) throw new ValidationException("Send the fields as an object.");
    String kind = pick(body, current, "kind");
    String done = pick(body, current, "doneOn");
    String due = pick(body, current, "nextDueOn");
    if (done == null && due == null) throw new ValidationException("Give the date it was done, or the date the doctor gave for it.");
    if ("custom".equals(kind) && pick(body, current, "customName") == null) throw new ValidationException("Name the check.");
  }

  private static String pick(JsonNode body, ObjectNode current, String f) {
    if (body.has(f)) return body.hasNonNull(f) && !body.get(f).asText().isBlank() ? body.get(f).asText() : null;
    return current != null && current.hasNonNull(f) ? current.get(f).asText() : null;
  }

  // ── Due and done ───────────────────────────────────────────────────────────

  /**
   * The state of each check a condition template lists: last done (from a lab result or a recorded
   * check), the doctor's date or the configured interval, and whether it is due.
   *
   * @param prefix config prefix of the template's intervals, such as {@code clinical.intervals.diabetes_t2.}
   */
  public ArrayNode statuses(PersonScope p, VitaTargetService.Context ctx, String prefix, List<String> names,
                            UUID conditionId, LocalDate today) {
    List<ConfigItem> items = config.list(p.owner());
    List<ObjectNode> checks = forms.live(p, VitaTable.CHECK);
    List<ObjectNode> reports = labs.reports(p);
    ArrayNode out = Json.mapper().createArrayNode();
    for (String name : names) {
      Sources src = SOURCES.getOrDefault(name, new Sources(List.of(), List.of()));
      ObjectNode n = out.addObject().put("check", name);

      // Last done: the latest confirmed lab result of one of its tests, or a recorded check.
      LocalDate lastDone = null;
      String lastFrom = null;
      BigDecimal lastValue = null;
      String lastCode = null;
      for (ObjectNode rep : reports) {
        if (!"confirmed".equals(rep.path("status").asText())) continue;
        LocalDate d = VitaLabUseCase.date(rep);
        if (d == null) continue;
        for (ObjectNode r : labs.results(p, UUID.fromString(rep.path("id").asText()))) {
          String code = r.path("testCode").asText(null);
          if (code != null && src.labTests().contains(code) && (lastDone == null || d.isAfter(lastDone))) {
            lastDone = d;
            lastFrom = "lab";
            lastCode = code;
            lastValue = r.hasNonNull("valueCanonical") ? r.get("valueCanonical").decimalValue() : null;
          }
        }
      }
      LocalDate doctorDue = null;
      LocalDate doctorDueSetOn = null;
      for (ObjectNode c : checks) {
        if (!src.kinds().contains(c.path("kind").asText())) continue;
        if (c.hasNonNull("doneOn")) {
          LocalDate d = LocalDate.parse(c.get("doneOn").asText());
          if (lastDone == null || d.isAfter(lastDone)) {
            lastDone = d;
            lastFrom = "check";
          }
        }
        if (c.hasNonNull("nextDueOn")) {
          LocalDate set = c.hasNonNull("doneOn") ? LocalDate.parse(c.get("doneOn").asText()) : null;
          // The doctor's most recent date wins.
          if (doctorDue == null || (set != null && (doctorDueSetOn == null || set.isAfter(doctorDueSetOn)))) {
            doctorDue = LocalDate.parse(c.get("nextDueOn").asText());
            doctorDueSetOn = set;
          }
        }
      }
      if (lastDone != null) n.put("lastDone", lastDone.toString()).put("lastDoneFrom", lastFrom);

      // The interval from config, if one is set.
      ConfigItem item = items.stream().filter(i -> i.key().equals(prefix + name)).findFirst().orElse(null);
      Period every = null;
      if (item != null && item.value() != null) {
        n.put("state", item.state()).put("label", item.label()).put("needsVerification", item.needsVerification());
        n.set("interval", item.value());
        JsonNode chosen = item.value();
        if (chosen.has("atTarget") || chosen.has("notAtTargetOrAfterChange")) {
          String which = hba1cBranch(p, ctx, lastCode, lastValue, lastDone, conditionId);
          if (which != null) n.put("intervalUsed", which);
          chosen = which == null ? null : chosen.get(which);
        }
        every = period(chosen);
      } else {
        n.put("state", VitaConfigService.NOT_SET_STATE).put("label", TargetEvaluator.NOT_SET_LABEL);
      }

      LocalDate dueOn = null;
      String dueFrom = null;
      if (doctorDue != null) {
        dueOn = doctorDue;
        dueFrom = "doctor";
      } else if (every != null && lastDone != null) {
        dueOn = lastDone.plus(every);
        dueFrom = "interval";
      }
      if (dueOn != null) {
        n.put("dueOn", dueOn.toString()).put("dueFrom", dueFrom);
        n.put("status", dueOn.isAfter(today) ? UPCOMING : DUE);
      } else if (every != null) {
        n.put("status", NO_RECORD).put("statusLabel", "No record yet");
      } else {
        n.put("status", TargetEvaluator.NOT_SET).put("statusLabel", TargetEvaluator.NOT_SET_LABEL);
      }
    }
    return out;
  }

  /**
   * Which HbA1c interval applies: "notAtTargetOrAfterChange" when the last HbA1c was outside the
   * target or a medicine for the condition changed since; "atTarget" when it was within it; null
   * when there is no target or no result to judge by.
   */
  private String hba1cBranch(PersonScope p, VitaTargetService.Context ctx, String code, BigDecimal value,
                             LocalDate lastDone, UUID conditionId) {
    if (!"hba1c".equals(code) || value == null) return null;
    if (conditionId != null && lastDone != null && medicineChangedSince(p, conditionId, lastDone)) return "notAtTargetOrAfterChange";
    RangeSet range = ctx.labRangeFor("hba1c");
    if (range == null) return null;
    var e = TargetEvaluator.evaluate(new TargetEvaluator.Reading("lab", value, null, null, null), range, List.of(), null);
    return TargetEvaluator.IN_RANGE.equals(e.status()) ? "atTarget" : "notAtTargetOrAfterChange";
  }

  private boolean medicineChangedSince(PersonScope p, UUID conditionId, LocalDate since) {
    Set<String> meds = new java.util.HashSet<>();
    records.list("vita_medicine", p.owner(), VitaRecordsUseCase.livePerson(p.personId())).stream()
        .filter(m -> conditionId.toString().equals(m.path("conditionId").asText(null)))
        .forEach(m -> meds.add(m.path("id").asText()));
    if (meds.isEmpty()) return false;
    return records.list("vita_medicine_event", p.owner(), Map.of("personId", p.personId())).stream()
        .filter(e -> meds.contains(e.path("medicineId").asText()))
        .filter(e -> Set.of("started", "dose_changed", "stopped").contains(e.path("kind").asText()))
        .anyMatch(e -> e.hasNonNull("onDate") && LocalDate.parse(e.get("onDate").asText()).isAfter(since));
  }

  /**
   * The period an interval names: "aboutEveryMonths: n", or "timesPerYearAtLeast: n" read as every
   * 12/n months (whole months only; anything else is not used). Null when it names neither.
   */
  static Period period(JsonNode n) {
    if (n == null || !n.isObject()) return null;
    if (n.path("aboutEveryMonths").isInt() && n.get("aboutEveryMonths").asInt() > 0) {
      return Period.ofMonths(n.get("aboutEveryMonths").asInt());
    }
    if (n.path("timesPerYearAtLeast").isInt()) {
      int t = n.get("timesPerYearAtLeast").asInt();
      if (t > 0 && 12 % t == 0) return Period.ofMonths(12 / t);
    }
    return null;
  }
}
