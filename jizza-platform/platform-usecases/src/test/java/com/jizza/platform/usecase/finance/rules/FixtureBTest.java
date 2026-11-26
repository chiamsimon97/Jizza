package com.jizza.platform.usecase.finance.rules;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jizza.platform.usecase.finance.rules.RuleResolver.Effective;
import com.jizza.platform.usecase.finance.rules.RuleResolver.Level;
import com.jizza.platform.usecase.finance.rules.RuleResolver.Row;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Fixture B (spec, "Test fixtures"): rule inheritance for Stock X, a mid cap held in Zerodha and
 * Upstox. Every effective value comes back with its source and lock.
 */
class FixtureBTest {

  static final ObjectMapper JSON = new ObjectMapper();

  static JsonNode p(String json) {
    try {
      return JSON.readTree(json);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  static Row set(String field, String params) {
    return new Row(field, "set", false, false, p(params));
  }

  static Row locked(String field, String params) {
    return new Row(field, "set", true, false, p(params));
  }

  static Row inherit(String field) {
    return new Row(field, "inherit", false, false, p("{}"));
  }

  static Row off(String field) {
    return new Row(field, "off", false, false, p("{}"));
  }

  static Map<String, Row> rows(Row... rs) {
    Map<String, Row> m = new LinkedHashMap<>();
    for (Row r : rs) m.put(r.field(), r);
    return m;
  }

  static final Level GOAL = new Level("goal", "g1", "Goal defaults", rows(
      set("stop_loss", "{\"kind\":\"trailing\",\"pct\":15}"),
      set("profit_booking", "{\"sellPct\":25,\"atGainPct\":40}"),
      set("rebalance_band", "{\"mode\":\"absolute\",\"points\":5}"),
      set("execution_mode", "{\"mode\":\"propose\"}")));

  static final Level EQUITY = new Level("asset_class", "n-eq", "Indian equity", rows(
      set("stop_loss", "{\"kind\":\"trailing\",\"pct\":12}"),
      inherit("profit_booking"),
      inherit("rebalance_band"),
      locked("execution_mode", "{\"mode\":\"propose\"}")));

  static final Level MID = new Level("sub_category", "n-mid", "Mid Cap", rows(
      inherit("stop_loss"),
      set("profit_booking", "{\"sellPct\":33,\"atGainPct\":30}"),
      set("rebalance_band", "{\"mode\":\"relative\",\"pct\":25}")));

  static final Level STOCK_X = new Level("instrument", "i-x", "Stock X", rows(
      set("stop_loss", "{\"kind\":\"fixed\",\"pct\":8}"),
      inherit("profit_booking")));

  static final Level STOCK_Y = new Level("instrument", "i-y", "Stock Y", rows());

  static final Level USER = new Level("user", null, "Your defaults", rows());
  static final Level KAI = RuleResolver.kaiLevel(p("""
      {"rebalance_band": {"state": "set", "params": {"mode": "five_twenty_five"}},
       "execution_mode": {"state": "set", "params": {"mode": "observe"}},
       "stop_loss": {"state": "off", "params": {}}}"""));

  static List<Level> chain(Level instrument, Level mid) {
    return List.of(instrument, mid, EQUITY, GOAL, USER, KAI);
  }

  @Test
  void stockXGetsEveryEffectiveValueWithItsSource() {
    Map<String, Effective> e = RuleResolver.resolve(chain(STOCK_X, MID)).fields();

    assertThat(e.get("stop_loss").value().path("kind").asText()).isEqualTo("fixed");
    assertThat(e.get("stop_loss").value().path("pct").asInt()).isEqualTo(8);
    assertThat(e.get("stop_loss").sourceName()).isEqualTo("Stock X");

    assertThat(e.get("profit_booking").value().path("sellPct").asInt()).isEqualTo(33);
    assertThat(e.get("profit_booking").value().path("atGainPct").asInt()).isEqualTo(30);
    assertThat(e.get("profit_booking").sourceLevel()).isEqualTo("sub_category");

    assertThat(e.get("rebalance_band").value().path("mode").asText()).isEqualTo("relative");
    assertThat(e.get("rebalance_band").value().path("pct").asInt()).isEqualTo(25);
    assertThat(e.get("rebalance_band").sourceName()).isEqualTo("Mid Cap");

    Effective exec = e.get("execution_mode");
    assertThat(exec.value().path("mode").asText()).isEqualTo("propose");
    assertThat(exec.sourceName()).isEqualTo("Indian equity");
    assertThat(exec.locked()).isTrue();
    assertThat(exec.lockedBy()).isEqualTo("Indian equity");
  }

  @Test
  void settingStockXToAutoIsRefusedBecauseIndianEquityLocksIt() {
    String why = RuleResolver.refusal(chain(STOCK_X, MID), 0, "execution_mode", set("execution_mode", "{\"mode\":\"auto\"}"));
    assertThat(why).isEqualTo("Indian equity locks Execution; it can't be loosened here.");
    // Observe is stricter than Propose, so it may be set below the lock.
    assertThat(RuleResolver.refusal(chain(STOCK_X, MID), 0, "execution_mode",
        set("execution_mode", "{\"mode\":\"observe\"}"))).isNull();
  }

  @Test
  void stockYInheritsEverything() {
    Map<String, Effective> e = RuleResolver.resolve(chain(STOCK_Y, MID)).fields();
    assertThat(e.get("stop_loss").sourceName()).isEqualTo("Indian equity");
    assertThat(e.get("stop_loss").value().path("pct").asInt()).isEqualTo(12);
    assertThat(e.get("profit_booking").sourceName()).isEqualTo("Mid Cap");
    assertThat(e.get("rebalance_band").sourceName()).isEqualTo("Mid Cap");
    assertThat(e.get("execution_mode").sourceName()).isEqualTo("Indian equity");
  }

  @Test
  void midCapStopLossOffMeansStockYHasNone() {
    Level midOff = new Level("sub_category", "n-mid", "Mid Cap", rows(
        off("stop_loss"),
        set("profit_booking", "{\"sellPct\":33,\"atGainPct\":30}"),
        set("rebalance_band", "{\"mode\":\"relative\",\"pct\":25}")));
    Effective sl = RuleResolver.resolve(chain(STOCK_Y, midOff)).fields().get("stop_loss");
    assertThat(sl.state()).isEqualTo("off");
    assertThat(sl.value()).isNull();
    assertThat(sl.sourceName()).isEqualTo("Mid Cap");
  }

  @Test
  void withNothingSetKaisDefaultsApply() {
    Map<String, Effective> e = RuleResolver.resolve(List.of(STOCK_Y, USER, KAI)).fields();
    assertThat(e.get("execution_mode").value().path("mode").asText()).isEqualTo("observe");
    assertThat(e.get("execution_mode").sourceLevel()).isEqualTo("kai");
    assertThat(e.get("stop_loss").state()).isEqualTo("off");
  }

  @Test
  void valuesAreChecked() {
    assertThat(RuleResolver.invalid("stop_loss", p("{\"kind\":\"fixed\",\"pct\":0}"))).isNotNull();
    assertThat(RuleResolver.invalid("rebalance_band", p("{\"mode\":\"relative\",\"pct\":25}"))).isNull();
    assertThat(RuleResolver.invalid("execution_mode", p("{\"mode\":\"yolo\"}"))).isNotNull();
  }
}
