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
import com.jizza.platform.usecase.finance.health.FinHealthUseCase;
import com.jizza.platform.usecase.finance.protection.FinProtectionUseCase;
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
 * the engines computed, open a Portfolio view, and mark an action done or snoozed. Only the agent
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
  public static final String OPEN_SCREEN = "finance_open_screen";
  public static final String UPDATE_ACTION = "finance_update_action";

  static final Map<String, ActionType> TYPES = Map.of(
      OVERVIEW, ActionType.READ, CASH_FLOW, ActionType.READ, LOANS, ActionType.READ,
      PROTECTION, ActionType.READ, OPEN_SCREEN, ActionType.DRAFT, UPDATE_ACTION, ActionType.UPDATE);

  static final List<String> SCREENS =
      List.of("home", "actions", "cash-flow", "loans", "protection", "net-worth", "people", "settings");

  private final AgentDirectory agents;
  private final ObjectProvider<FinHealthUseCase> health;
  private final ObjectProvider<FinCashFlowUseCase> cashFlow;
  private final ObjectProvider<FinLoansUseCase> loans;
  private final ObjectProvider<FinProtectionUseCase> protection;
  private final boolean enabled;
  private final List<ToolSpec> specs;

  public FinanceToolProvider(AgentDirectory agents, ObjectProvider<FinHealthUseCase> health,
                             ObjectProvider<FinCashFlowUseCase> cashFlow,
                             ObjectProvider<FinLoansUseCase> loans,
                             ObjectProvider<FinProtectionUseCase> protection,
                             @Value("${jizza.finance.enabled:false}") boolean enabled) {
    this.agents = agents;
    this.health = health;
    this.cashFlow = cashFlow;
    this.loans = loans;
    this.protection = protection;
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
                List.of("actionId", "status")), true, false));
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
        case OPEN_SCREEN -> ToolResult.ok(openScreen(kai.get(), args));
        case UPDATE_ACTION -> ToolResult.ok(updateAction(user, args));
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
