package com.jizza.platform.usecase.finance.debt;

import static org.assertj.core.api.Assertions.assertThat;

import com.jizza.platform.usecase.finance.cashflow.NetWorthService;
import com.jizza.platform.usecase.finance.debt.DebtPlanner.GoldLoan;
import com.jizza.platform.usecase.finance.debt.DebtPlanner.MonthRow;
import com.jizza.platform.usecase.finance.debt.DebtPlanner.Plan;
import com.jizza.platform.usecase.finance.health.MoneyHealthService;
import com.jizza.platform.usecase.finance.model.Household;
import com.jizza.platform.usecase.finance.model.Household.Asset;
import com.jizza.platform.usecase.finance.model.Household.Loan;
import com.jizza.platform.usecase.finance.model.Household.Person;
import com.jizza.platform.usecase.finance.model.Household.Pledge;
import com.jizza.platform.usecase.finance.model.KaiConfig;
import com.jizza.platform.usecase.finance.model.KaiDefaults;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Fixture F of the spec: debts, a gold loan and family borrowing. Every numbered result. */
class FixtureFTest {

  static final LocalDate AS_OF = LocalDate.of(2026, 11, 24);
  static final KaiConfig CFG = KaiDefaults.config();

  static final Loan CARD = new Loan("card", "me", "CREDIT_CARD", "Credit card", bd(60_000), bd(42),
      "revolving", null, null, null, null, false, List.of());
  static final Loan GOLD = new Loan("gold", "me", "GOLD_LOAN", "Gold loan", bd(200_000), bd(9.5),
      "bullet", null, LocalDate.of(2026, 6, 1), LocalDate.of(2027, 5, 31), bd(200_000), false,
      List.of(new Pledge("gold", bd(30), 22, bd(280_000), AS_OF)));
  static final Loan FAMILY = new Loan("family", "me", "FAMILY_FRIEND", "Brother", bd(100_000), bd(0),
      "flexible", null, null, LocalDate.of(2027, 3, 31), null, true, List.of());
  static final Loan HOME = new Loan("home", "me", "HOME", "Home loan", bd(3_500_000), bd(8.5),
      "emi", null, null, null, null, false, List.of());
  static final List<Loan> LOANS = List.of(CARD, GOLD, FAMILY, HOME);

  static Plan plan() {
    return DebtPlanner.plan(LOANS, bd(70_000), AS_OF, CFG, null, BigDecimal.ZERO);
  }

  @Test
  void result1_goldLoanMaturityLtvAndAccruedInterest() {
    GoldLoan g = plan().goldLoans().get(0);
    assertThat(g.termDays()).isEqualTo(364);
    assertThat(g.maturityInterest()).isEqualByComparingTo("18948");
    assertThat(g.maturityAmount()).isEqualByComparingTo("218948");
    assertThat(g.ltvPct()).isEqualByComparingTo("78.2");
    assertThat(g.capPct()).isEqualByComparingTo("85");
    assertThat(g.warn()).isFalse();
    assertThat(g.accruedDays()).isEqualTo(176);
    assertThat(g.accruedInterest()).isEqualByComparingTo("9162");
    assertThat(g.ltvBasis()).isEqualTo("amountRepayableAtMaturity");
  }

  @Test
  void result2_decemberPaysTheCardInFullAndTheRestToTheFamilyLoan() {
    MonthRow dec = plan().month(YearMonth.of(2026, 12));
    assertThat(dec.payments().get("card")).isEqualByComparingTo("60000");
    assertThat(dec.payments().get("family")).isEqualByComparingTo("10000");
    assertThat(dec.payments()).doesNotContainKey("gold");
    assertThat(dec.investable()).isEqualByComparingTo("0");
  }

  @Test
  void result3_januaryToMarch() {
    Plan p = plan();
    for (int m = 1; m <= 3; m++) {
      MonthRow row = p.month(YearMonth.of(2027, m));
      assertThat(row.payments().get("family")).as("family %s", m).isEqualByComparingTo("30000");
      assertThat(row.payments().get("gold")).as("gold %s", m).isEqualByComparingTo("40000");
      assertThat(row.investable()).isEqualByComparingTo("0");
    }
  }

  @Test
  void result4_aprilAndMay() {
    Plan p = plan();
    for (int m = 4; m <= 5; m++) {
      MonthRow row = p.month(YearMonth.of(2027, m));
      assertThat(row.payments().get("gold")).isEqualByComparingTo("49474");
      assertThat(row.payments()).doesNotContainKeys("family", "card");
    }
  }

  @Test
  void result5_investableAmountAndHomeLoanStaysOnEmi() {
    Plan p = plan();
    assertThat(p.month(YearMonth.of(2026, 12)).investable()).isEqualByComparingTo("0");
    for (int m = 1; m <= 3; m++) assertThat(p.month(YearMonth.of(2027, m)).investable()).isEqualByComparingTo("0");
    assertThat(p.month(YearMonth.of(2027, 4)).investable()).isEqualByComparingTo("20526");
    assertThat(p.month(YearMonth.of(2027, 5)).investable()).isEqualByComparingTo("20526");
    assertThat(p.month(YearMonth.of(2027, 6)).investable()).isEqualByComparingTo("70000");
    assertThat(p.order()).filteredOn(o -> o.loanId().equals("home"))
        .extracting(DebtPlanner.OrderItem::step).containsExactly(DebtPlanner.KEEP_EMI);
    assertThat(p.order()).extracting(DebtPlanner.OrderItem::loanId)
        .containsExactly("card", "family", "gold", "home");
  }

  @Test
  void result6_aboutRs8200OfCardInterestAvoided() {
    BigDecimal saved = plan().interestSaved();
    assertThat(saved.divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100)))
        .isEqualByComparingTo("8200");
  }

  @Test
  void result7_pledgedGoldCountsInGrossNetWorthButNotAsEmergencyMoney() {
    Household h = new Household(AS_OF, List.of(new Person("me", "Me", "self", null, true, false)),
        List.of(), List.of(), LOANS, List.of(),
        List.of(new Asset("g", "me", "gold_physical", "gold", bd(280_000), true, true, true)),
        List.of(), List.of(), false, null, Set.of());
    var nw = NetWorthService.compute(h, null);
    assertThat(nw.assets()).isEqualByComparingTo("280000");
    assertThat(nw.pledged()).isEqualByComparingTo("280000");
    assertThat(MoneyHealthService.liquidEmergency(h)).isEqualByComparingTo("0");
  }

  @Test
  void result8_brothersNameIsOnlyTheOwnersNote() {
    assertThat(FAMILY.label()).isEqualTo("Brother");
    assertThat(DebtPlanner.sharedLabel(FAMILY)).isEqualTo("Family / friend");
    assertThat(DebtPlanner.sharedLabel(CARD)).isEqualTo("Credit card");
  }

  @Test
  void ratesAreNormalised() {
    assertThat(RateNormaliser.effectiveAnnualPct("₹2 per ₹100 per month", null, null)).isEqualByComparingTo("24");
    assertThat(RateNormaliser.effectiveAnnualPct("2 per 100", null, null)).isEqualByComparingTo("24");
    assertThat(RateNormaliser.effectiveAnnualPct("1.5% a month", null, null)).isEqualByComparingTo("18");
    assertThat(RateNormaliser.effectiveAnnualPct("14% a year", null, null)).isEqualByComparingTo("14");
    assertThat(RateNormaliser.effectiveAnnualPct("9.5", null, null)).isEqualByComparingTo("9.5");
    assertThat(RateNormaliser.effectiveAnnualPct("no interest", null, null)).isEqualByComparingTo("0");
    // A 12-month consumer loan at 10% flat is about 17.97% on the reducing balance.
    assertThat(RateNormaliser.effectiveAnnualPct("10% flat", "flat", 12)).isBetween(bd(17.9), bd(18.0));
    assertThat(RateNormaliser.effectiveAnnualPct("", null, null)).isNull();
  }

  static BigDecimal bd(double v) { return BigDecimal.valueOf(v); }
}
