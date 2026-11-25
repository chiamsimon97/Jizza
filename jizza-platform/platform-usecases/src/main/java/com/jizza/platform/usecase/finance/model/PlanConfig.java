package com.jizza.platform.usecase.finance.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The settings Phase 1b's engines read from {@code fin_config} (the user's override, else Kai's
 * default): the allocation tables of the plan builder (2A.8), horizon buckets, inflation profiles,
 * expected returns and the goal library. As with {@link KaiConfig}, nothing has a default of its
 * own here: a missing field is a configuration error.
 */
public record PlanConfig(
    List<AgeRow> horizonByAge,
    Map<String, BigDecimal> shortMix,
    Map<String, Map<String, BigDecimal>> midByRisk,
    List<LongRow> longByAge,
    Map<String, Map<String, BigDecimal>> equitySubByRisk,
    int stpMonths,
    int shortBelowMonths,
    int midBelowMonths,
    Map<String, BigDecimal> inflation,
    Map<String, BigDecimal> returns,
    List<JsonNode> library) {

  public static final String HORIZON_BY_AGE = "allocation.horizon_by_age";
  public static final String SHORT = "allocation.short";
  public static final String MID_BY_RISK = "allocation.mid_by_risk";
  public static final String LONG_BY_AGE = "allocation.long_by_age";
  public static final String EQUITY_SUB = "allocation.equity_sub_by_risk";
  public static final String LUMPSUM = "allocation.lumpsum";
  public static final String BUCKETS = "buckets.months";
  public static final String INFLATION = "inflation.profiles";
  public static final String RETURNS = "returns.expected";
  public static final String LIBRARY = "goals.library";

  public static final List<String> KEYS = List.of(HORIZON_BY_AGE, SHORT, MID_BY_RISK, LONG_BY_AGE,
      EQUITY_SUB, LUMPSUM, BUCKETS, INFLATION, RETURNS, LIBRARY);

  public static final List<String> RISKS = List.of("conservative", "moderate", "aggressive");

  /** One row of the age table for the horizon split (Short, Mid, Long percentages). */
  public record AgeRow(int fromAge, int toAge, BigDecimal shortPct, BigDecimal midPct,
                       BigDecimal longPct) {
    public String label() {
      return toAge >= 120 ? "Age " + fromAge + "+" : fromAge == 0 ? "Age under " + (toAge + 1)
          : "Age " + fromAge + "–" + toAge;
    }
  }

  /** One row of the Long bucket by age: equity (with its range by risk), debt and gold. */
  public record LongRow(int fromAge, int toAge, BigDecimal equity, BigDecimal equityMin,
                        BigDecimal equityMax, BigDecimal debt, BigDecimal gold) {
    public String label() {
      return toAge >= 120 ? "Age " + fromAge + "+" : fromAge == 0 ? "Age under " + (toAge + 1)
          : "Age " + fromAge + "–" + toAge;
    }
  }

  public static PlanConfig from(Map<String, JsonNode> values) {
    List<AgeRow> horizon = new ArrayList<>();
    for (JsonNode r : need(values, HORIZON_BY_AGE)) {
      horizon.add(new AgeRow(r.get("fromAge").asInt(), r.get("toAge").asInt(), num(r, "short"),
          num(r, "mid"), num(r, "long")));
    }
    List<LongRow> longRows = new ArrayList<>();
    for (JsonNode r : need(values, LONG_BY_AGE)) {
      longRows.add(new LongRow(r.get("fromAge").asInt(), r.get("toAge").asInt(), num(r, "equity"),
          num(r, "equityMin"), num(r, "equityMax"), num(r, "debt"), num(r, "gold")));
    }
    JsonNode buckets = need(values, BUCKETS);
    List<JsonNode> library = new ArrayList<>();
    need(values, LIBRARY).forEach(library::add);
    return new PlanConfig(horizon, numbers(need(values, SHORT)), byRisk(need(values, MID_BY_RISK)),
        longRows, byRisk(need(values, EQUITY_SUB)), need(values, LUMPSUM).get("stpMonths").asInt(),
        buckets.get("shortBelow").asInt(), buckets.get("midBelow").asInt(),
        numbers(need(values, INFLATION)), numbers(need(values, RETURNS)), library);
  }

  /** The age row for an age; under the first row uses the first row (2A.8: under 20 uses 20–29). */
  public AgeRow horizonFor(int age) {
    for (AgeRow r : horizonByAge) if (age >= r.fromAge() && age <= r.toAge()) return r;
    return age < horizonByAge.get(0).fromAge() ? horizonByAge.get(0) : horizonByAge.get(horizonByAge.size() - 1);
  }

  public LongRow longFor(int age) {
    for (LongRow r : longByAge) if (age >= r.fromAge() && age <= r.toAge()) return r;
    return age < longByAge.get(0).fromAge() ? longByAge.get(0) : longByAge.get(longByAge.size() - 1);
  }

  /** Short below 36 months, Mid below 84, Long after. */
  public String bucketFor(long monthsToTarget) {
    if (monthsToTarget < shortBelowMonths) return "short";
    if (monthsToTarget < midBelowMonths) return "mid";
    return "long";
  }

  public BigDecimal inflationPct(String key) {
    BigDecimal v = inflation.get(key == null ? "cpi" : key);
    if (v == null) throw new IllegalStateException("inflation.profiles has no " + key);
    return v;
  }

  public BigDecimal returnPct(String assetClass) {
    BigDecimal v = returns.get(assetClass);
    if (v == null) throw new IllegalStateException("returns.expected has no " + assetClass);
    return v;
  }

  public JsonNode libraryCard(String type) {
    return library.stream().filter(c -> type.equals(c.path("type").asText())).findFirst()
        .orElseThrow(() -> new IllegalStateException("goals.library has no " + type));
  }

  private static Map<String, Map<String, BigDecimal>> byRisk(JsonNode n) {
    Map<String, Map<String, BigDecimal>> out = new LinkedHashMap<>();
    for (String risk : RISKS) {
      JsonNode r = n.get(risk);
      if (r == null) throw new IllegalStateException("Setting has no " + risk);
      out.put(risk, numbers(r));
    }
    return out;
  }

  private static Map<String, BigDecimal> numbers(JsonNode n) {
    Map<String, BigDecimal> out = new LinkedHashMap<>();
    n.fields().forEachRemaining(e -> {
      if (e.getValue().isNumber()) out.put(e.getKey(), e.getValue().decimalValue());
    });
    return out;
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
