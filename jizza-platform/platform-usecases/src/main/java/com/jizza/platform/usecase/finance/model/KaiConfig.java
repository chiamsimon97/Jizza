package com.jizza.platform.usecase.finance.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The configuration values the Phase 1a engines use, read from {@code fin_config} (the user's
 * override, else Kai's default). Nothing here has a default of its own: a missing field is a
 * configuration error, so no threshold quietly lives in code.
 */
public record KaiConfig(
    Map<String, CheckRule> checks,
    Survival survival,
    TermMultiple termMultiple,
    Prepay prepay,
    GoldLtv goldLtv) {

  public static final String HEALTH_CHECKS = "health.checks";
  public static final String SURVIVAL = "survival.order";
  public static final String TERM_MULTIPLE = "protection.term_multiple";
  public static final String PREPAY = "debt.prepay_or_invest";
  public static final String GOLD_LTV = "regulatory.gold_loan_ltv";

  /** The keys an engine run needs, for the config snapshot stored with a health run. */
  public static final List<String> KEYS = List.of(HEALTH_CHECKS, SURVIVAL, TERM_MULTIPLE, PREPAY, GOLD_LTV);

  /**
   * One check's thresholds. For "higher is better" checks green means value ≥ green; for the
   * others (debt, protection gaps, spending) green means value ≤ green.
   */
  public record CheckRule(BigDecimal weight, BigDecimal green, BigDecimal amber, BigDecimal extra) {}

  public record Survival(BigDecimal starterBufferMonths, BigDecimal fullEmergencyMonths,
                         BigDecimal highInterestRatePct, BigDecimal emergencyShareOfRestPct,
                         BigDecimal healthCoverFloor) {}

  public record TermMultiple(int upToAge, BigDecimal multipleUpToAge, BigDecimal multipleAfter) {}

  public record Prepay(BigDecimal repayAbovePct, BigDecimal splitFromPct, BigDecimal splitPrepayPct,
                       String method) {}

  /** upTo null is the open top tier. */
  public record LtvTier(BigDecimal upTo, BigDecimal capPct) {}

  public record GoldLtv(List<LtvTier> tiers, BigDecimal warnAtPct, String bulletLtvBasis,
                        int warnDaysBeforeMaturity) {}

  /** Reads the configuration from its JSON values, keyed as in {@link #KEYS}. */
  public static KaiConfig from(Map<String, JsonNode> values) {
    JsonNode checks = need(values, HEALTH_CHECKS);
    Map<String, CheckRule> rules = new LinkedHashMap<>();
    checks.fields().forEachRemaining(e -> {
      JsonNode c = e.getValue();
      JsonNode extra = c.has("maxRatePct") ? c.get("maxRatePct") : c.get("premiumFlagPctOfIncome");
      rules.put(e.getKey(), new CheckRule(num(c, "weight"), num(c, "green"), num(c, "amber"),
          extra == null ? null : extra.decimalValue()));
    });
    JsonNode s = need(values, SURVIVAL);
    JsonNode t = need(values, TERM_MULTIPLE);
    JsonNode p = need(values, PREPAY);
    JsonNode g = need(values, GOLD_LTV);
    List<LtvTier> tiers = new ArrayList<>();
    for (JsonNode tier : g.get("tiers")) {
      JsonNode upTo = tier.get("upTo");
      tiers.add(new LtvTier(upTo == null || upTo.isNull() ? null : upTo.decimalValue(),
          num(tier, "capPct")));
    }
    return new KaiConfig(rules,
        new Survival(num(s, "starterBufferMonths"), num(s, "fullEmergencyMonths"),
            num(s, "highInterestRatePct"), num(s, "emergencyShareOfRestPct"),
            num(s, "healthCoverFloor")),
        new TermMultiple(t.get("upToAge").asInt(), num(t, "multipleUpToAge"), num(t, "multipleAfter")),
        new Prepay(num(p, "repayAbovePct"), num(p, "splitFromPct"), num(p, "splitPrepayPct"),
            p.path("method").asText("avalanche")),
        new GoldLtv(tiers, num(g, "warnAtPct"), g.path("bulletLtvBasis").asText(),
            g.get("warnDaysBeforeMaturity").asInt()));
  }

  public CheckRule check(String key) {
    CheckRule r = checks.get(key);
    if (r == null) throw new IllegalStateException("health.checks has no " + key);
    return r;
  }

  private static JsonNode need(Map<String, JsonNode> values, String key) {
    JsonNode v = values.get(key);
    if (v == null || v.isNull()) throw new IllegalStateException("Missing setting " + key);
    return v;
  }

  private static BigDecimal num(JsonNode n, String field) {
    JsonNode v = n.get(field);
    if (v == null || !v.isNumber()) throw new IllegalStateException("Setting field " + field + " is missing");
    return v.decimalValue();
  }
}
