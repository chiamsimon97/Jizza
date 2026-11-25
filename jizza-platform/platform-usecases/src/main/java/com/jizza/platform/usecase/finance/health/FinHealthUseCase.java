package com.jizza.platform.usecase.finance.health;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.finance.FinanceRecords;
import com.jizza.engine.api.util.Json;
import com.jizza.platform.usecase.finance.health.ActionPlanService.Action;
import com.jizza.platform.usecase.finance.health.MoneyHealthService.Check;
import com.jizza.platform.usecase.finance.health.MoneyHealthService.Health;
import com.jizza.platform.usecase.finance.sharing.FinScopeResolver;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Money Health home and the action plan (5.8): runs the check on the owner's current data, keeps
 * a run whenever the result changes (so the trend shows each change, including after an import),
 * and keeps one action item per amber or red check. At most three actions are open; the rest
 * wait in "Later". An action whose check has turned green closes itself.
 */
@Service
public class FinHealthUseCase {

  public static final int OPEN_AT_ONCE = 3;
  private static final Set<String> LIVE = Set.of("open", "later", "snoozed");
  private static final int TREND_POINTS = 12;

  private final FinanceRecords records;
  private final HouseholdLoader loader;
  private final FinScopeResolver scopes;
  private final AuditLog audit;

  public FinHealthUseCase(FinanceRecords records, HouseholdLoader loader, FinScopeResolver scopes,
                          AuditLog audit) {
    this.records = records;
    this.loader = loader;
    this.scopes = scopes;
    this.audit = audit;
  }

  public record TrendPoint(Instant at, BigDecimal score) {}

  /**
   * @param flags the two unscored flags (nominee missing, no will) and whether any figure used
   *     needs verification
   */
  public record HealthView(LocalDate asOf, Integer score, BigDecimal rawScore, List<Check> checks,
                           List<TrendPoint> trend, Map<String, Boolean> flags,
                           BigDecimal surplus, BigDecimal essentialMonthly, List<ObjectNode> topActions) {}

  /** The current result; stores a run and refreshes the actions when anything changed. */
  public HealthView health(UUID caller) {
    var scope = scopes.own(caller);
    return compute(scope.ownerUserId(), caller, false);
  }

  /** "Run the check now": always stores a run. */
  public HealthView run(UUID caller) {
    var scope = scopes.own(caller);
    return compute(scope.ownerUserId(), caller, true);
  }

  /** Used by the monthly job, as the owner. */
  public HealthView runFor(UUID owner) {
    return compute(owner, owner, true);
  }

  public List<ObjectNode> actions(UUID caller) {
    var scope = scopes.own(caller);
    compute(scope.ownerUserId(), caller, false);
    return sorted(records.list("fin_action_item", scope.ownerUserId(), Map.of()));
  }

  /** Done, Snooze, Not for me (with a reason), or back to open. */
  public ObjectNode updateAction(UUID caller, UUID id, String status, LocalDate snoozedUntil, String reason) {
    var scope = scopes.own(caller);
    UUID owner = scope.ownerUserId();
    ObjectNode current = records.get("fin_action_item", owner, id)
        .orElseThrow(() -> new NotFoundException("Action", id));
    ObjectNode values = Json.obj();
    switch (status == null ? "" : status) {
      case "done" -> values.put("status", "done");
      case "snoozed" -> {
        if (snoozedUntil == null || !snoozedUntil.isAfter(HouseholdLoader.today())) {
          throw new ValidationException("Pick a date after today to snooze until.");
        }
        if (snoozedUntil.isAfter(HouseholdLoader.today().plusYears(1))) {
          throw new ValidationException("Snooze for a year at most.");
        }
        values.put("status", "snoozed").put("snoozedUntil", snoozedUntil.toString());
      }
      case "dismissed" -> {
        if (reason == null || reason.isBlank()) throw new ValidationException("Say why this is not for you.");
        if (reason.length() > 200) throw new ValidationException("Keep the reason under 200 characters.");
        values.put("status", "dismissed").put("dismissReason", reason.trim());
      }
      case "open" -> values.put("status", "later").putNull("snoozedUntil").putNull("dismissReason");
      default -> throw new ValidationException("status must be done, snoozed, dismissed or open.");
    }
    if ("open".equals(status)) {
      String key = current.path("actionKey").asText();
      boolean liveTwin = records.list("fin_action_item", owner, Map.of("actionKey", key)).stream()
          .anyMatch(r -> !r.path("id").asText().equals(id.toString()) && LIVE.contains(r.path("status").asText()));
      if (liveTwin) throw new ValidationException("This action is already on the plan.");
    }
    records.update("fin_action_item", owner, id, values);
    audit.record(owner, ActorType.USER, caller, "finance.action." + status, current.path("actionKey").asText(), null);
    rebalance(owner);
    return records.get("fin_action_item", owner, id).orElseThrow();
  }

  // ── internals ───────────────────────────────────────────────────────────────

  private HealthView compute(UUID owner, UUID actor, boolean forceStore) {
    var loaded = loader.load(owner);
    Health h = MoneyHealthService.run(loaded.household(), loaded.config());
    List<Action> actions = ActionPlanService.plan(loaded.household(), h, loaded.config());

    JsonNode checks = Json.toNode(h.checks());
    List<ObjectNode> runs = records.list("fin_health_check_run", owner, Map.of());
    ObjectNode last = runs.isEmpty() ? null : runs.get(runs.size() - 1);
    boolean changed = last == null || !signature(checks).equals(signature(last.get("checks")));
    if (changed || forceStore) {
      ObjectNode run = Json.obj();
      if (h.rawScore() != null) run.put("score", h.rawScore());
      run.set("checks", checks);
      run.set("configSnapshot", Json.toNode(loaded.configValues()));
      records.insert("fin_health_check_run", owner, run);
      runs = records.list("fin_health_check_run", owner, Map.of());
    }
    syncActions(owner, actions);

    List<TrendPoint> trend = new ArrayList<>();
    for (ObjectNode r : runs.subList(Math.max(0, runs.size() - TREND_POINTS), runs.size())) {
      trend.add(new TrendPoint(Instant.parse(normaliseInstant(r.path("runAt").asText())),
          HouseholdLoader.dec(r, "score")));
    }
    boolean verify = loaded.verification().values().stream().anyMatch(Boolean::booleanValue);
    Map<String, Boolean> flags = Map.of("nomineeMissing", h.flags().nomineeMissing(),
        "willMissing", h.flags().willMissing(), "needsVerification", verify);
    List<ObjectNode> top = sorted(records.list("fin_action_item", owner, Map.of("status", "open")));
    return new HealthView(loaded.household().asOf(), h.score(), h.rawScore(), h.checks(), trend,
        flags, h.cashFlow().surplus(), h.cashFlow().essential(), top);
  }

  /** What a run says, for telling whether the result changed: each check's status and figure. */
  static String signature(JsonNode checks) {
    StringBuilder sb = new StringBuilder();
    if (checks != null) {
      for (JsonNode c : checks) {
        JsonNode shown = c.get("shown");
        sb.append(c.path("key").asText()).append(':').append(c.path("status").asText()).append(':')
            .append(shown == null || shown.isNull() ? "" : shown.decimalValue().stripTrailingZeros().toPlainString())
            .append(';');
      }
    }
    return sb.toString();
  }

  /** Postgres writes "2026-11-25T10:00:00.12+00:00"; Instant wants a Z or a full offset. */
  static String normaliseInstant(String s) {
    return java.time.OffsetDateTime.parse(s).toInstant().toString();
  }

  void syncActions(UUID owner, List<Action> proposed) {
    List<ObjectNode> all = records.list("fin_action_item", owner, Map.of());
    Map<String, ObjectNode> live = new HashMap<>();
    Set<String> dismissed = new java.util.HashSet<>();
    for (ObjectNode r : all) {
      String st = r.path("status").asText();
      if (LIVE.contains(st)) live.put(r.path("actionKey").asText(), r);
      if ("dismissed".equals(st)) dismissed.add(r.path("actionKey").asText());
    }
    int rank = 0;
    Set<String> wanted = new java.util.HashSet<>();
    for (Action a : proposed) {
      rank++;
      wanted.add(a.key());
      ObjectNode values = Json.obj()
          .put("layer", a.layer()).put("title", a.title()).put("reason", a.reason())
          .put("impactPoints", a.impact()).put("rank", rank);
      values.set("details", Json.toNode(a.details()));
      if (a.sourceCheck() != null) values.put("sourceCheck", a.sourceCheck());
      if (a.personId() != null) values.put("personId", a.personId());
      if (a.dueDate() != null) values.put("dueDate", a.dueDate().toString());
      ObjectNode existing = live.get(a.key());
      if (existing != null) {
        if (differs(existing, values)) records.update("fin_action_item", owner, uuid(existing), values);
      } else if (!dismissed.contains(a.key())) {
        values.put("actionKey", a.key()).put("status", "later");
        try {
          records.insert("fin_action_item", owner, values);
        } catch (DataIntegrityViolationException raced) {
          // Another request added the same action a moment ago; nothing to do.
        }
      }
    }
    for (var e : live.entrySet()) {
      String st = e.getValue().path("status").asText();
      if (!wanted.contains(e.getKey()) && !"snoozed".equals(st)) {
        records.update("fin_action_item", owner, uuid(e.getValue()), Json.obj().put("status", "done"));
      }
    }
    rebalance(owner);
  }

  /** The first three live actions by rank are open; the rest wait in "Later". */
  void rebalance(UUID owner) {
    LocalDate today = HouseholdLoader.today();
    List<ObjectNode> live = sorted(records.list("fin_action_item", owner, Map.of())).stream()
        .filter(r -> LIVE.contains(r.path("status").asText())).toList();
    int open = 0;
    for (ObjectNode r : live) {
      String st = r.path("status").asText();
      LocalDate until = HouseholdLoader.date(r, "snoozedUntil");
      if ("snoozed".equals(st) && until != null && until.isAfter(today)) continue;
      String want = open < OPEN_AT_ONCE ? "open" : "later";
      if (want.equals("open")) open++;
      if (!want.equals(st)) {
        ObjectNode v = Json.obj().put("status", want);
        if ("snoozed".equals(st)) v.putNull("snoozedUntil");
        records.update("fin_action_item", owner, uuid(r), v);
      }
    }
  }

  private static boolean differs(ObjectNode existing, ObjectNode values) {
    var it = values.fields();
    while (it.hasNext()) {
      var e = it.next();
      JsonNode now = existing.get(e.getKey());
      JsonNode want = e.getValue();
      if (now == null) return true;
      if (now.isNumber() && want.isNumber()) {
        if (now.decimalValue().compareTo(want.decimalValue()) != 0) return true;
      } else if (!now.equals(want)) {
        return true;
      }
    }
    return false;
  }

  static List<ObjectNode> sorted(List<ObjectNode> rows) {
    List<ObjectNode> out = new ArrayList<>(rows);
    out.sort(Comparator.comparingInt((ObjectNode r) -> statusOrder(r.path("status").asText()))
        .thenComparingInt(r -> r.path("rank").asInt()));
    return out;
  }

  private static int statusOrder(String s) {
    return switch (s) {
      case "open" -> 0;
      case "later" -> 1;
      case "snoozed" -> 2;
      case "done" -> 3;
      default -> 4;
    };
  }

  private static UUID uuid(ObjectNode r) {
    return UUID.fromString(r.path("id").asText());
  }
}
