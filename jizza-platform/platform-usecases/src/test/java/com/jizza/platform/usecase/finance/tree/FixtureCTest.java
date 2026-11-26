package com.jizza.platform.usecase.finance.tree;

import static org.assertj.core.api.Assertions.assertThat;

import com.jizza.platform.usecase.finance.tree.AssetTree.Drift;
import com.jizza.platform.usecase.finance.tree.AssetTree.Node;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Fixture C (spec, "Test fixtures"): drift after a rally. Indian equity 50% (Mid Cap 30%, Large
 * Cap 50%, Small Cap 20% of it), Gold 10%, Debt 40%. Bands are checked at every node.
 */
class FixtureCTest {

  static final List<Node> TREE = List.of(
      new Node("eq", null, "asset_class", "equity", "Indian equity", bd("50"), null),
      new Node("mid", "eq", "sub_category", "mid_cap", "Mid Cap", bd("30"), null),
      new Node("large", "eq", "sub_category", "large_cap_index", "Large Cap", bd("50"), null),
      new Node("small", "eq", "sub_category", "small_cap", "Small Cap", bd("20"), null),
      new Node("gold", null, "asset_class", "gold", "Gold", bd("10"), null),
      new Node("debt", null, "asset_class", "debt", "Debt", bd("40"), null));

  @Test
  void theBandsFollowTheFiveTwentyFiveRule() {
    Map<String, Drift> d = byId(AssetTree.drift(TREE, Map.of(), bd("100"), Map.of()));
    assertBand(d.get("eq"), "45", "55");
    assertBand(d.get("mid"), "11.25", "18.75");
    assertBand(d.get("large"), "20", "30");
    assertBand(d.get("small"), "7.5", "12.5");
    assertBand(d.get("gold"), "7.5", "12.5");
  }

  @Test
  void afterTheRallyMidCapIsAboveAndGoldBelowAndNothingElseIsFlagged() {
    // Values out of a goal worth 100: Mid Cap 21, Large Cap 23.2, Small Cap 10.6 (equity 54.8), Gold 7, Debt 38.2.
    Map<String, BigDecimal> held = Map.of("mid", bd("21"), "large", bd("23.2"), "small", bd("10.6"),
        "gold", bd("7"), "debt", bd("38.2"));
    Map<String, Drift> d = byId(AssetTree.drift(TREE, held, bd("100"), Map.of()));
    assertThat(d.get("eq").actualPct()).isEqualByComparingTo("54.8");
    assertThat(d.get("mid").status()).isEqualTo("above");
    assertThat(d.get("gold").status()).isEqualTo("below");
    assertThat(d.values().stream().filter(x -> !x.status().equals("inside")).map(Drift::id))
        .containsExactlyInAnyOrder("mid", "gold");
    assertThat(d.get("mid").driftPts()).isEqualByComparingTo("6");
  }

  @Test
  void siblingsMustAddUpToOneHundred() {
    assertThat(AssetTree.problems(TREE)).isEmpty();
    List<Node> bad = List.of(TREE.get(0), new Node("mid", "eq", "sub_category", "mid_cap", "Mid Cap", bd("30"), null),
        new Node("large", "eq", "sub_category", "large_cap_index", "Large Cap", bd("60"), null),
        TREE.get(4), TREE.get(5));
    assertThat(AssetTree.problems(bad)).containsExactly("The parts of Indian equity add up to 90%, not 100%.");
  }

  @Test
  void aNodesOwnBandReplacesTheDefault() {
    var bands = Map.of("mid", new AssetTree.Band("absolute", bd("2"), null, null));
    Map<String, Drift> d = byId(AssetTree.drift(TREE, Map.of(), bd("100"), bands));
    assertBand(d.get("mid"), "13", "17");
  }

  static void assertBand(Drift d, String lo, String hi) {
    assertThat(d.bandLow()).as(d.name()).isEqualByComparingTo(lo);
    assertThat(d.bandHigh()).as(d.name()).isEqualByComparingTo(hi);
  }

  static Map<String, Drift> byId(List<Drift> ds) {
    return ds.stream().collect(Collectors.toMap(Drift::id, x -> x));
  }

  static BigDecimal bd(String v) {
    return new BigDecimal(v);
  }
}
