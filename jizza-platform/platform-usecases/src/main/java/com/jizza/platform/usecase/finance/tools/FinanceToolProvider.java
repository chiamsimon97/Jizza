package com.jizza.platform.usecase.finance.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.autonomy.ActionTypeSource;
import com.jizza.engine.api.dto.AgentDto;
import com.jizza.engine.api.enums.ActionType;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.orchestrator.AgentDirectory;
import com.jizza.engine.api.tool.JsonSchemas;
import com.jizza.engine.api.tool.ToolCall;
import com.jizza.engine.api.tool.ToolProvider;
import com.jizza.engine.api.tool.ToolResult;
import com.jizza.engine.api.tool.ToolSpec;
import com.jizza.engine.api.util.Json;
import com.jizza.platform.usecase.finance.cashflow.FinCashFlowUseCase;
import com.jizza.platform.usecase.finance.debt.FinLoansUseCase;
import com.jizza.platform.usecase.finance.goal.FinGoalUseCase;
import com.jizza.platform.usecase.finance.health.FinHealthUseCase;
import com.jizza.platform.usecase.finance.imports.ImportService;
import com.jizza.platform.usecase.finance.protection.FinProtectionUseCase;
import com.jizza.platform.usecase.finance.tax.FinTaxUseCase;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Kai's own tools (6.1), Phase 1a: read the Money Health result, cash flow, loans and protection
 * the engines computed, open a Portfolio view, and mark an action done or snoozed. From chat Kai
 * can also sync a connected broker into the Portfolio (a review) and save it once the user says so. Only the agent
 * whose key is {@code finance} gets them, only while the finance switch is on, and only on the
 * caller's own data ({@code scope: me}). No tool moves money, edits a goal or places an order.
 *
 * <p>Every figure in a result comes from the engines, so Kai quotes it rather than working it out.
 * Arguments are kept out of the audit log: they can carry the user's own money details.
 */
@Component
public class FinanceToolProvider implements ToolProvider, ActionTypeSource {

  public static final String AGENT_KEY = "finance";
  public static final String OVERVIEW = "finance_overview";
  public static final String CASH_FLOW = "finance_cash_flow";
  public static final String LOANS = "finance_loans";
  public static final String PROTECTION = "finance_protection";
  public static final String GOALS = "finance_goals";
  public static final String TAX = "finance_tax";
  public static final String OPEN_SCREEN = "finance_open_screen";
  public static final String UPDATE_ACTION = "finance_update_action";
  public static final String HOLDINGS = "finance_holdings";
  public static final String ALLOCATION = "finance_allocation";
  public static final String EXPLAIN_VALUE = "finance_explain_value";
  public static final String SYNC = "finance_sync_connected";
  public static final String SAVE_SYNC = "finance_save_sync";

  static final Map<String, ActionType> TYPES = Map.ofEntries(
      Map.entry(OVERVIEW, ActionType.READ), Map.entry(CASH_FLOW, ActionType.READ), Map.entry(LOANS, ActionType.READ),
      Map.entry(PROTECTION, ActionType.READ), Map.entry(GOALS, ActionType.READ), Map.entry(TAX, ActionType.READ),
      Map.entry(HOLDINGS, ActionType.READ), Map.entry(ALLOCATION, ActionType.READ), Map.entry(EXPLAIN_VALUE, ActionType.READ),
      Map.entry(OPEN_SCREEN, ActionType.DRAFT), Map.entry(UPDATE_ACTION, ActionType.UPDATE),
      Map.entry(SYNC, ActionType.DRAFT), Map.entry(SAVE_SYNC, ActionType.UPDATE));

  static final List<String> SCREENS = List.of("home", "actions", "goals", "plan-builder", "cash-flow",
      "loans", "protection", "tax", "net-worth", "people", "settings", "holdings", "allocation", "asset-tree",
      "lists", "rules", "connect");

  private final AgentDirectory agents;
  private final ObjectProvider<FinHealthUseCase> health;
  private final ObjectProvider<FinCashFlowUseCase> cashFlow;
  private final ObjectProvider<FinLoansUseCase> loans;
  private final ObjectProvider<FinProtectionUseCase> protection;
  private final ObjectProvider<FinGoalUseCase> goals;
  private final ObjectProvider<FinTaxUseCase> tax;
  private final ObjectProvider<com.jizza.platform.usecase.finance.ledger.HoldingsUseCase> holdings;
  private final ObjectProvider<com.jizza.platform.usecase.finance.tree.TreeService> trees;
  private final ObjectProvider<com.jizza.platform.usecase.finance.rules.RulesService> rules;
  private final ObjectProvider<com.jizza.platform.usecase.finance.sync.BrokerSyncService> brokers;
  private final ObjectProvider<com.jizza.platform.usecase.finance.imports.ImportService> imports;
  private final boolean enabled;
  private final List<ToolSpec> specs;

  public FinanceToolProvider(AgentDirectory agents, ObjectProvider<FinHealthUseCase> health,
                             ObjectProvider<FinCashFlowUseCase> cashFlow,
                             ObjectProvider<FinLoansUseCase> loans,
                             ObjectProvider<FinProtectionUseCase> protection,
                             ObjectProvider<FinGoalUseCase> goals,
                             ObjectProvider<FinTaxUseCase> tax,
                             ObjectProvider<com.jizza.platform.usecase.finance.ledger.HoldingsUseCase> holdings,
                             ObjectProvider<com.jizza.platform.usecase.finance.tree.TreeService> trees,
                             ObjectProvider<com.jizza.platform.usecase.finance.rules.RulesService> rules,
                             ObjectProvider<com.jizza.platform.usecase.finance.sync.BrokerSyncService> brokers,
                             ObjectProvider<com.jizza.platform.usecase.finance.imports.ImportService> imports,
                             @Value("${jizza.finance.enabled:false}") boolean enabled) {
    this.agents = agents;
    this.health = health;
    this.cashFlow = cashFlow;
    this.loans = loans;
    this.protection = protection;
    this.goals = goals;
    this.tax = tax;
    this.holdings = holdings;
    this.trees = trees;
    this.rules = rules;
    this.brokers = brokers;
    this.imports = imports;
    this.enabled = enabled;
    ObjectNode scope = JsonSchemas.string("Whose data: \"me\" (the default) is you and the people you manage.");
    scope.putArray("enum").add("me");
    this.specs = List.of(
        read(OVERVIEW, "The user's Money Health: score out of 100, the six checks with status, net "
            + "worth, monthly surplus and the top actions, as Kai's engines computed them. Quote "
            + "these figures; do not work out your own.", scope),
        read(CASH_FLOW, "Monthly income by person, spending by need, want and save, the surplus "
            + "and where the surplus goes by the priority ladder.", scope),
        read(LOANS, "Every loan and borrowing with its effective yearly rate (and gold-loan LTV), "
            + "the repayment order, the next months of the repayment plan, the debt-free date "
            + "and the interest the plan saves.", scope),
        read(PROTECTION, "Term and health cover against what each person needs, with the gaps.", scope),
        read(GOALS, "The user's goals: target in today's rupees and at the target date, bucket "
            + "(Short, Mid, Long), funded % and the monthly SIP each needs, with the return and "
            + "inflation assumed. Analysis, not a forecast. Quote these figures.", scope),
        new ToolSpec(TAX, "Old against new tax regime for one person this financial year, from "
            + "their income and the deductions they entered. Figures marked needsVerification "
            + "must be checked with a CA.",
            JsonSchemas.object(props("scope", scope,
                "personId", JsonSchemas.string("A person's id from the profile; leave out for the user")),
                List.of()), false, false),
        new ToolSpec(HOLDINGS, "The user's holdings: units, average cost, the latest dated price, value "
            + "and gain per instrument, which goals each holding is earmarked to (and what is still "
            + "Unassigned), and the holdings that need a look. Read only; earmarks are changed on "
            + "screen by the user, never by Kai.",
            JsonSchemas.object(props("scope", scope,
                "goalId", JsonSchemas.string("Only the holdings earmarked to this goal (optional)")),
                List.of()), false, false),
        new ToolSpec(ALLOCATION, "One goal's target allocation against what it holds: per node its "
            + "target, actual, band (the 5/25 rule unless a rule sets another) and whether it is "
            + "above or below band. Without goalId, the whole picture by asset class.",
            JsonSchemas.object(props("scope", scope,
                "goalId", JsonSchemas.string("The goal's id from finance_goals (optional)")),
                List.of()), false, false),
        new ToolSpec(EXPLAIN_VALUE, "Where a rule value comes from for a holding (or a tree node) in a "
            + "goal: each rule's effective value, the level it comes from, and any lock.",
            JsonSchemas.object(props("scope", scope,
                "goalId", JsonSchemas.string("The goal's id"),
                "instrumentId", JsonSchemas.string("The holding's instrument id (optional)"),
                "nodeId", JsonSchemas.string("A tree node's id (optional)")),
                List.of("goalId")), false, false),
        new ToolSpec(OPEN_SCREEN, "Give the user a link card into a Portfolio view, such as the "
            + "action plan or the loans view. Changes nothing.",
            JsonSchemas.object(props("scope", scope, "screen", enumOf("The view to open", SCREENS)),
                List.of("screen")), false, false),
        new ToolSpec(UPDATE_ACTION, "Mark one of the user's actions done, or snooze it until a "
            + "date, when the user says so. Use the action id from finance_overview.",
            JsonSchemas.object(props("scope", scope,
                "actionId", JsonSchemas.string("The action's id"),
                "status", enumOf("done or snoozed", List.of("done", "snoozed")),
                "snoozedUntil", JsonSchemas.string("For snoozed: yyyy-mm-dd, after today")),
                List.of("actionId", "status")), true, false),
        new ToolSpec(SYNC, "When the user asks to sync, refresh or bring in their holdings from a "
            + "connected broker (such as Zerodha Kite on Connectors): reads the broker's holdings, "
            + "mutual funds and the cash in the account, and fills them into the Portfolio's own "
            + "sections with no mapping by hand. A broker not linked yet is linked to the user's "
            + "demat account at that broker, or a new one is made for them. Nothing is saved yet: "
            + "each result is a review with its counts. Tell the user what was found and ask before "
            + "saving with finance_save_sync. Prefer this over calling the broker's own tools.",
            JsonSchemas.object(props("scope", scope,
                "broker", JsonSchemas.string("Part of the connected server's name, e.g. Zerodha (optional; all brokers when left out)")),
                List.of()), true, false),
        new ToolSpec(SAVE_SYNC, "Save a sync from finance_sync_connected into the Portfolio once the "
            + "user has said to save it: holdings, mutual funds and the account's cash go into "
            + "Holdings and Net worth, and the import can be undone under Connect. Never call it "
            + "without the user's yes in this conversation.",
            JsonSchemas.object(props("scope", scope,
                "batchId", JsonSchemas.string("The batchId finance_sync_connected gave")),
                List.of("batchId")), true, false));
  }

  @Override
  public List<ToolSpec> toolsFor(UUID agentId) {
    return kai(agentId).isPresent() ? specs : List.of();
  }

  @Override
  public boolean supports(UUID agentId, String toolName) {
    return TYPES.containsKey(toolName) && kai(agentId).isPresent();
  }

  @Override
  public boolean redactArguments(String toolName) {
    return true;
  }

  @Override
  public Optional<ActionType> actionTypeOf(UUID agentId, String toolRef) {
    return Optional.ofNullable(TYPES.get(toolRef));
  }

  @Override
  public ToolResult invoke(UUID agentId, ToolCall call) {
    Optional<AgentDto> kai = kai(agentId);
    if (kai.isEmpty()) return ToolResult.error("Only Kai has the finance tools");
    UUID user = kai.get().userId();
    JsonNode args = call.args() == null ? Json.obj() : call.args();
    String scope = args.path("scope").asText("me");
    if (!"me".equals(scope)) {
      return ToolResult.error("Only your own data (scope \"me\") can be read here. Family data "
          + "is not shared with Kai in this version.");
    }
    try {
      return switch (call.name()) {
        case OVERVIEW -> ToolResult.ok(overview(user));
        case CASH_FLOW -> ToolResult.ok(Json.toNode(cashFlow.getObject().cashFlow(user, null)));
        case LOANS -> ToolResult.ok(loans(user));
        case PROTECTION -> ToolResult.ok(Json.toNode(protection.getObject().protection(user)));
        case GOALS -> ToolResult.ok(goals(user));
        case TAX -> ToolResult.ok(Json.toNode(tax.getObject().forPerson(user,
            args.hasNonNull("personId") ? args.get("personId").asText() : null)));
        case HOLDINGS -> ToolResult.ok(holdings.getObject().list(user, null, uuid(args, "goalId")));
        case ALLOCATION -> ToolResult.ok(Json.toNode(trees.getObject().allocation(user, uuid(args, "goalId"))));
        case EXPLAIN_VALUE -> ToolResult.ok(explain(user, args));
        case OPEN_SCREEN -> ToolResult.ok(openScreen(kai.get(), args));
        case UPDATE_ACTION -> ToolResult.ok(updateAction(user, args));
        case SYNC -> ToolResult.ok(sync(kai.get(), args));
        case SAVE_SYNC -> ToolResult.ok(saveSync(user, args));
        default -> ToolResult.error("Unknown finance tool " + call.name());
      };
    } catch (ValidationException | NotFoundException e) {
      return ToolResult.error(e.getMessage());
    }
  }

  private JsonNode overview(UUID user) {
    var h = health.getObject().health(user);
    var nw = cashFlow.getObject().netWorth(user, null);
    ObjectNode out = Json.obj();
    out.put("asOf", h.asOf().toString());
    if (h.score() == null) out.putNull("score"); else out.put("score", h.score());
    ArrayNode checks = out.putArray("checks");
    for (var c : h.checks()) {
      ObjectNode n = checks.addObject().put("check", c.key()).put("status", c.status()).put("unit", c.unit());
      if (c.shown() != null) n.put("value", c.shown());
      n.put("target", c.target()).put("points", c.points()).put("weight", c.weight());
    }
    out.set("flags", Json.toNode(h.flags()));
    out.put("monthlySurplus", h.surplus());
    out.put("netWorth", nw.netWorth()).put("assets", nw.assets()).put("liabilities", nw.liabilities());
    ArrayNode actions = out.putArray("topActions");
    for (ObjectNode a : h.topActions()) {
      actions.addObject().put("id", a.path("id").asText()).put("title", a.path("title").asText())
          .put("reason", a.path("reason").asText()).put("layer", a.path("layer").asText())
          .set("impactPoints", a.get("impactPoints"));
    }
    out.put("note", "Figures are Kai's engine results. Unknown checks had no data and are not counted.");
    return out;
  }

  private JsonNode goals(UUID user) {
    ObjectNode out = Json.obj();
    ArrayNode list = out.putArray("goals");
    for (var g : goals.getObject().list(user)) {
      ObjectNode row = g.goal();
      var f = g.funding();
      ObjectNode n = list.addObject().put("id", row.path("id").asText()).put("name", row.path("name").asText())
          .put("type", row.path("type").asText()).put("priority", row.path("priorityTier").asText())
          .put("status", row.path("status").asText()).put("targetDate", row.path("targetDate").asText())
          .put("owner", g.ownerName()).put("bucket", f.bucket()).put("riskProfile", g.risk());
      n.set("targetToday", row.get("targetAmountToday"));
      n.put("targetAtDate", f.futureTarget()).put("projected", f.projected())
          .put("fundedPct", com.jizza.platform.usecase.finance.model.Inr.one(f.fundedPct()))
          .put("requiredMonthlySip", f.requiredSip()).put("inflationPct", f.inflationPct())
          .put("expectedReturnPct", com.jizza.platform.usecase.finance.model.Inr.one(f.blendedReturnNow()));
      n.set("savedSoFar", row.get("currentValue"));
      n.set("monthlyGoingIn", row.get("monthlyContribution"));
      if (g.moveNote() != null) n.put("moves", g.moveNote());
    }
    out.put("note", "Deterministic projection on Kai's default returns; analysis, not a forecast. "
        + "Nothing is bought: goals are tracked only.");
    return out;
  }

  private JsonNode loans(UUID user) {
    ObjectNode out = Json.obj();
    ArrayNode list = out.putArray("loans");
    for (ObjectNode l : loans.getObject().list(user)) {
      ObjectNode n = list.addObject().put("id", l.path("id").asText()).put("label", l.path("label").asText())
          .put("kind", l.path("kind").asText()).put("status", l.path("status").asText());
      n.set("outstanding", l.get("outstanding"));
      n.set("effectiveAnnualRatePct", l.get("effectiveAnnualRate"));
      n.set("monthlyPayment", l.get("emiOrInterest"));
      n.set("dueDate", l.get("maturityOrPromisedDate"));
      if (l.hasNonNull("ltv")) n.set("goldLoan", l.get("ltv"));
    }
    var plan = loans.getObject().plan(user, null, null);
    out.put("asOf", plan.asOf().toString());
    out.put("monthlySurplus", plan.monthlySurplus());
    out.set("order", Json.toNode(plan.plan().order()));
    out.set("nextMonths", Json.toNode(plan.plan().monthly().subList(0, Math.min(6, plan.plan().monthly().size()))));
    out.put("interestSaved", plan.plan().interestSaved());
    out.put("debtFreeBy", plan.plan().debtFreeBy() == null ? null : plan.plan().debtFreeBy().toString());
    out.put("figuresNeedVerification", plan.figuresNeedVerification());
    return out;
  }

  private JsonNode explain(UUID user, JsonNode args) {
    UUID goal = uuid(args, "goalId");
    if (goal == null) throw new ValidationException("goalId is needed.");
    var v = rules.getObject().view(user, goal, uuid(args, "instrumentId"), uuid(args, "nodeId"));
    ObjectNode out = Json.obj().put("goal", v.goalName()).put("instrument", v.instrumentName());
    ArrayNode chain = out.putArray("chain");
    v.chain().forEach(c -> chain.add(c.path("name").asText()));
    ArrayNode fields = out.putArray("rules");
    for (var f : v.fields()) {
      var e = f.effective();
      ObjectNode x = fields.addObject().put("rule", f.label()).put("state", e.state()).put("from", e.sourceName())
          .put("locked", e.locked());
      if (e.value() != null) x.set("value", e.value());
      if (e.lockedBy() != null) x.put("lockedBy", e.lockedBy());
    }
    return out;
  }

  private static UUID uuid(JsonNode args, String field) {
    if (!args.hasNonNull(field) || args.get(field).asText().isBlank()) return null;
    try {
      return UUID.fromString(args.get(field).asText());
    } catch (IllegalArgumentException e) {
      throw new ValidationException(field + " must be an id.");
    }
  }

  private JsonNode openScreen(AgentDto kai, JsonNode args) {
    String screen = args.path("screen").asText("home");
    if (!SCREENS.contains(screen)) throw new ValidationException("screen must be one of " + String.join(", ", SCREENS));
    return Json.obj().put("type", "link").put("label", "Open " + screen.replace('-', ' '))
        .put("url", "/agents/" + kai.id() + "?tab=portfolio&view=" + screen);
  }

  private JsonNode updateAction(UUID user, JsonNode args) {
    String status = args.path("status").asText();
    if (!Set.of("done", "snoozed").contains(status)) throw new ValidationException("status must be done or snoozed.");
    UUID id;
    try {
      id = UUID.fromString(args.path("actionId").asText());
    } catch (IllegalArgumentException e) {
      throw new ValidationException("actionId must be an action's id.");
    }
    LocalDate until = null;
    if (args.hasNonNull("snoozedUntil")) {
      try {
        until = LocalDate.parse(args.get("snoozedUntil").asText());
      } catch (Exception e) {
        throw new ValidationException("snoozedUntil must be a date (yyyy-mm-dd).");
      }
    }
    ObjectNode a = health.getObject().updateAction(user, id, status, until, null);
    return Json.obj().put("id", a.path("id").asText()).put("title", a.path("title").asText())
        .put("status", a.path("status").asText());
  }

  private JsonNode sync(AgentDto kai, JsonNode args) {
    String hint = args.hasNonNull("broker") ? args.get("broker").asText() : null;
    var results = brokers.getObject().syncFromChat(kai.userId(), hint);
    ObjectNode out = Json.obj();
    ArrayNode list = out.putArray("brokers");
    for (var r : results) {
      ObjectNode n = list.addObject().put("broker", r.server()).put("account", r.accountName())
          .put("linkedNow", r.linkedNow()).put("accountCreated", r.accountCreated())
          .put("status", r.result().status()).put("message", r.result().message());
      if (r.result().batchId() != null) {
        n.put("batchId", r.result().batchId());
        ObjectNode stats = (ObjectNode) imports.getObject().get(kai.userId(), UUID.fromString(r.result().batchId())).path("stats");
        n.put("holdings", stats.path("holdings").size());
        java.math.BigDecimal total = java.math.BigDecimal.ZERO;
        int shares = 0;
        int funds = 0;
        for (JsonNode h : stats.path("holdings")) {
          total = total.add(h.path("value").decimalValue());
          if ("mf".equals(h.path("subCategory").asText())) funds++; else shares++;
        }
        n.put("shares", shares).put("mutualFunds", funds).put("holdingsValue", total);
        if (stats.has("balance")) n.set("cash", stats.path("balance").path("amount"));
        n.put("unreadable", stats.path("unreadable").size());
        if (stats.has("counts")) n.set("counts", stats.get("counts"));
      } else {
        n.put("holdings", r.result().holdings());
      }
    }
    out.set("review", openScreen(kai, Json.obj().put("screen", "connect")));
    out.put("note", "Nothing is saved yet. Summarise per broker, then ask the user whether to save; "
        + "goal earmarks stay the user's to set on screen.");
    return out;
  }

  private JsonNode saveSync(UUID user, JsonNode args) {
    UUID id = uuid(args, "batchId");
    if (id == null) throw new ValidationException("batchId is needed.");
    ImportService svc = imports.getObject();
    ObjectNode b = svc.get(user, id);
    if (!"broker_holdings".equals(b.path("kind").asText()) || !b.path("stats").has("syncKey")) {
      throw new ValidationException("Only a sync from a connected broker can be saved from chat. Review file imports under Connect.");
    }
    ObjectNode saved = svc.commit(user, id, null);
    return Json.obj().put("batchId", saved.path("id").asText()).put("status", saved.path("status").asText())
        .put("note", "Saved. It can be undone under Connect, Import history.");
  }

  private Optional<AgentDto> kai(UUID agentId) {
    if (!enabled || agentId == null) return Optional.empty();
    return agents.byId(agentId).filter(a -> AGENT_KEY.equals(a.key()));
  }

  private static ToolSpec read(String name, String description, ObjectNode scope) {
    return new ToolSpec(name, description, JsonSchemas.object(props("scope", scope), List.of()), false, false);
  }

  private static Map<String, JsonNode> props(Object... pairs) {
    Map<String, JsonNode> map = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) map.put((String) pairs[i], (JsonNode) pairs[i + 1]);
    return map;
  }

  private static ObjectNode enumOf(String description, List<String> values) {
    ObjectNode node = JsonSchemas.string(description);
    ArrayNode allowed = node.putArray("enum");
    values.forEach(allowed::add);
    return node;
  }
}
