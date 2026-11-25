package com.jizza.platform.usecase.finance.goal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.platform.usecase.finance.goal.FundingStatusService.Funding;
import com.jizza.platform.usecase.finance.goal.FundingStatusService.GoalInput;
import com.jizza.platform.usecase.finance.health.HouseholdLoader;
import com.jizza.platform.usecase.finance.model.Household.Person;
import com.jizza.platform.usecase.finance.model.PlanConfig;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads goal rows ({@code fin_plan}) into the funding engine's inputs: whose goal it is, the risk
 * profile it uses, and the owner's age. Shared by the goal screens, the plan builder and the Money
 * Health check, so every place computes a goal's funding the same way.
 */
public final class GoalMath {

  private GoalMath() {}

  /** Goals that count: active or paused (not a draft, achieved or archived). */
  public static final Set<String> LIVE = Set.of("active", "paused");

  /** The risk profile a goal uses and where it came from: goal, person, self or default. */
  public record RiskUsed(String profile, String source) {}

  /** The latest risk profile per person id, from the assessment rows (oldest first). */
  public static Map<String, String> latestRisk(List<ObjectNode> assessments) {
    Map<String, String> out = new LinkedHashMap<>();
    for (ObjectNode a : assessments) out.put(a.path("personId").asText(), a.path("profile").asText());
    return out;
  }

  public static RiskUsed riskFor(JsonNode goal, Map<String, String> latest, String selfId) {
    if (goal.hasNonNull("riskProfile")) return new RiskUsed(goal.get("riskProfile").asText(), "goal");
    String owner = goal.path("ownerPersonId").asText(selfId);
    if (latest.containsKey(owner)) return new RiskUsed(latest.get(owner), owner.equals(selfId) ? "self" : "person");
    if (latest.containsKey(selfId)) return new RiskUsed(latest.get(selfId), "self");
    return new RiskUsed("moderate", "default");
  }

  /** The age that shapes the goal's Long mix: the owner's, or for a minor the managing adult's. */
  public static Integer ageFor(JsonNode goal, Map<String, Person> people, String selfId, LocalDate asOf) {
    Person owner = people.get(goal.path("ownerPersonId").asText(selfId));
    Person self = people.get(selfId);
    if (owner == null || owner.dob() == null || "child".equals(owner.kind())) {
      return self == null ? null : self.ageOn(asOf);
    }
    return owner.ageOn(asOf);
  }

  public static Funding funding(JsonNode goal, Map<String, Person> people, Map<String, String> latest,
                                String selfId, LocalDate asOf, PlanConfig cfg) {
    RiskUsed risk = riskFor(goal, latest, selfId);
    GoalInput in = new GoalInput(LocalDate.parse(goal.path("targetDate").asText()),
        HouseholdLoader.dec(goal, "targetAmountToday"), goal.path("inflationKey").asText("cpi"),
        HouseholdLoader.dec(goal, "currentValue"), HouseholdLoader.dec(goal, "monthlyContribution"),
        HouseholdLoader.dec(goal, "stepupPct"), ageFor(goal, people, selfId, asOf), risk.profile());
    return FundingStatusService.fund(in, asOf, cfg);
  }

  /** Weighted funded % of the live Important goals; null when there are none. */
  public static BigDecimal importantFundedPct(List<ObjectNode> goals, Map<String, Person> people,
                                              Map<String, String> latest, String selfId,
                                              LocalDate asOf, PlanConfig cfg) {
    List<Funding> important = new ArrayList<>();
    for (ObjectNode g : goals) {
      if (!LIVE.contains(g.path("status").asText()) || !"important".equals(g.path("priorityTier").asText())) continue;
      important.add(funding(g, people, latest, selfId, asOf, cfg));
    }
    return FundingStatusService.weighted(important);
  }
}
