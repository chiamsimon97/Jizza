package com.jizza.platform.usecase.finance.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jizza.platform.usecase.finance.ledger.Earmarks.Claim;
import com.jizza.platform.usecase.finance.ledger.TaxLotLedger.Lot;
import com.jizza.platform.usecase.finance.ledger.TaxLotLedger.Sale;
import com.jizza.platform.usecase.finance.ledger.TaxLotLedger.TermRule;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Fixture A (spec, "Test fixtures"): 100 HDFC Bank shares in one Zerodha demat, earmarked
 * 30 / 30 / 40 to three goals. Price ₹2,000, valued 24 Nov 2026, charges ignored.
 */
class FixtureATest {

  static final LocalDate VALUED = LocalDate.of(2026, 11, 24);
  static final BigDecimal PRICE = new BigDecimal("2000");
  static final TermRule EQUITY = TermRule.months(12);

  static final List<Lot> LOTS = List.of(
      // Listed out of order on purpose: FIFO is by date, not by the order lots arrive.
      new Lot("C", LocalDate.of(2026, 9, 15), true, bd(30), bd(30), bd(1900)),
      new Lot("A", LocalDate.of(2023, 3, 15), true, bd(40), bd(40), bd(1600)),
      new Lot("B", LocalDate.of(2024, 8, 15), true, bd(30), bd(30), bd(1550)));

  static final List<Claim> EARMARKS = List.of(
      new Claim("P1", bd(30)), new Claim("P2", bd(30)), new Claim("P3", bd(40)));

  @Test
  void theEarmarksValidateAgainstTheLots() {
    assertThat(Earmarks.problem(EARMARKS, TaxLotLedger.remaining(LOTS))).isNull();
    assertThat(Earmarks.problem(List.of(new Claim("P1", bd(30)), new Claim("P2", bd(30)), new Claim("P3", bd(30))),
        bd(100))).isEqualTo("The goals add up to 90 units; the account holds 100.");
  }

  @Test
  void lotsAreSoldOldestFirstWithTheirTerms() {
    assertThat(TaxLotLedger.fifo(LOTS)).extracting(Lot::id).containsExactly("A", "B", "C");
    assertThat(TaxLotLedger.term(LOTS.get(1), VALUED, EQUITY)).isEqualTo("lt");
    assertThat(TaxLotLedger.term(LOTS.get(2), VALUED, EQUITY)).isEqualTo("lt");
    assertThat(TaxLotLedger.term(LOTS.get(0), VALUED, EQUITY)).isEqualTo("st");
    assertThat(TaxLotLedger.term(LOTS.get(0), LocalDate.of(2027, 9, 16), EQUITY)).isEqualTo("lt");
  }

  @Test
  void reEarmarkThenSellThirtyGivesTwelveThousandLongTermToTheSellingGoal() {
    // 1. Re-earmark 10 units P3 → P1 at ₹2,000: no trade; P1's ₹20,000 cash leg goes to P3.
    var moved = Earmarks.reassign(EARMARKS, "P3", "P1", bd(10));
    assertThat(moved.claims()).containsExactly(new Claim("P1", u(40)), new Claim("P2", u(30)), new Claim("P3", u(30)));
    BigDecimal cashLeg = PRICE.multiply(bd(10));
    assertThat(cashLeg).isEqualByComparingTo("20000");

    // 2–3. Net SELL 30, attributed to P3: FIFO takes 30 of lot A.
    var afterGoal = Earmarks.sellFrom(moved.claims(), "P3", bd(30));
    Sale sale = TaxLotLedger.sell(LOTS, bd(30), PRICE.multiply(bd(30)), VALUED, EQUITY);
    assertThat(sale.parts()).hasSize(1);
    assertThat(sale.parts().get(0).lotId()).isEqualTo("A");
    assertThat(sale.parts().get(0).term()).isEqualTo("lt");
    assertThat(sale.gain()).isEqualByComparingTo("12000");
    assertThat(sale.proceeds()).isEqualByComparingTo("60000");

    // 5. Earmarks P1 40 / P2 30 / P3 0; lots A 10, B 30, C 30; the invariant holds.
    assertThat(afterGoal.claims()).containsExactly(new Claim("P1", u(40)), new Claim("P2", u(30)));
    assertThat(TaxLotLedger.fifo(sale.after())).extracting(l -> l.remaining().intValue()).containsExactly(10, 30, 30);
    assertThat(Earmarks.total(afterGoal.claims())).isEqualByComparingTo(TaxLotLedger.remaining(sale.after()));
    // P3 is still worth ₹4,00,000: its ₹80,000 HDFC slice became ₹60,000 proceeds plus the ₹20,000 cash leg.
    assertThat(sale.proceeds().add(cashLeg)).isEqualByComparingTo("80000");
  }

  @Test
  void aGoalCannotSellUnitsItDoesNotClaim() {
    assertThatThrownBy(() -> Earmarks.sellFrom(EARMARKS, "P1", bd(31))).hasMessageContaining("fewer than 31");
    assertThatThrownBy(() -> TaxLotLedger.sell(LOTS, bd(101), null, VALUED, EQUITY)).hasMessageContaining("fewer than the 101");
  }

  @Test
  void aSaleOutsideKaiComesFromUnassignedFirstThenProRata() {
    List<Claim> withSpare = List.of(new Claim("P1", bd(30)), new Claim("P2", bd(30)), new Claim("U", bd(10)),
        new Claim("P3", bd(30)));
    var r = Earmarks.reduce(withSpare, bd(16), "U");
    // Unassigned gives its 10, then 6 more pro rata 30:30:30 → 2 each.
    assertThat(r.claims()).containsExactly(new Claim("P1", u(28)), new Claim("P2", u(28)), new Claim("P3", u(28)));
    assertThat(Earmarks.total(r.claims())).isEqualByComparingTo("84");
  }

  @Test
  void aSplitMultipliesEveryEarmarkAndKeepsTheTotal() {
    var r = Earmarks.scale(EARMARKS, bd(500));
    assertThat(r.claims()).containsExactly(new Claim("P1", u(150)), new Claim("P2", u(150)), new Claim("P3", u(200)));
    var odd = Earmarks.scale(List.of(new Claim("P1", bd(1)), new Claim("P2", bd(1)), new Claim("P3", bd(1))), bd(2));
    assertThat(Earmarks.total(odd.claims())).isEqualByComparingTo("2");
  }

  static BigDecimal bd(long v) {
    return BigDecimal.valueOf(v);
  }

  static BigDecimal u(long v) {
    return BigDecimal.valueOf(v).setScale(TaxLotLedger.UNITS);
  }
}
