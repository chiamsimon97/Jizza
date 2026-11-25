package com.jizza.platform.usecase.finance.health;

import static org.assertj.core.api.Assertions.assertThat;

import com.jizza.platform.usecase.finance.health.ActionPlanService.Action;
import com.jizza.platform.usecase.finance.health.MoneyHealthService.Health;
import com.jizza.platform.usecase.finance.model.Household;
import com.jizza.platform.usecase.finance.model.Household.Asset;
import com.jizza.platform.usecase.finance.model.Household.BudgetLine;
import com.jizza.platform.usecase.finance.model.Household.Income;
import com.jizza.platform.usecase.finance.model.Household.Loan;
import com.jizza.platform.usecase.finance.model.Household.Person;
import com.jizza.platform.usecase.finance.model.Household.Policy;
import com.jizza.platform.usecase.finance.model.KaiConfig;
import com.jizza.platform.usecase.finance.model.KaiDefaults;
import com.jizza.platform.usecase.finance.protection.ProtectionCalculator.PersonCover;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Fixture D of the spec: Money Health for a two-earner household. The spec describes it at
 * Family scope; the engines take the same plain records either way, so this test never touches
 * shares or the database.
 */
class FixtureDTest {

  static final LocalDate AS_OF = LocalDate.of(2026, 11, 24);
  static final KaiConfig CFG = KaiDefaults.config();

  static Household household(Set<String> known) {
    var me = new Person("me", "Me", "self", LocalDate.of(1992, 6, 1), true, false);
    var spouse = new Person("spouse", "Spouse", "spouse", LocalDate.of(1994, 6, 1), true, false);
    var child = new Person("child", "Child", "child", LocalDate.of(2020, 6, 1), false, true);
    return new Household(AS_OF, List.of(me, spouse, child),
        List.of(new Income("me", bd(160_000), bd(120_000)), new Income("spouse", bd(75_000), bd(60_000))),
        List.of(new BudgetLine("Food and bills", "need", bd(40_000)),
            new BudgetLine("Other spend", "want", bd(50_000)),
            new BudgetLine("SIPs", "save", bd(30_000))),
        List.of(
            new Loan("home", "me", "HOME", "Home loan", bd(3_500_000), bd(8.5), "emi", bd(30_000),
                null, null, null, false, List.of()),
            new Loan("personal", "me", "PERSONAL", "Personal loan", bd(150_000), bd(14), "emi",
                bd(8_000), null, null, null, false, List.of())),
        List.of(
            new Policy("term", "me", "term", bd(5_000_000), null, "yearly", false, List.of(), null),
            new Policy("group", "me", "health", bd(500_000), null, "yearly", true,
                List.of("me", "spouse", "child"), null)),
        List.of(new Asset("liquid", "me", "savings_balance", "cash", bd(250_000), true, false, true)),
        List.of(), List.of(), false, bd(78), known);
  }

  static final Set<String> ALL = Set.of(Household.INCOME, Household.SPENDING, Household.LOANS,
      Household.INSURANCE, Household.SAVINGS);

  @Test
  void everyCheckValueStatusAndPoints() {
    Health h = MoneyHealthService.run(household(ALL), CFG);

    assertCheck(h, "emergencyFund", "3.2", "amber", "10");
    assertCheck(h, "debt", "16.2", "red", "0");
    assertCheck(h, "savingsRate", "16.7", "amber", "10");
    assertCheck(h, "goalFunding", "78.0", "amber", "7.5");
    assertCheck(h, "protection", "3.0", "red", "0");
    assertCheck(h, "spendingControl", "43.3", "green", "10");

    assertThat(h.rawScore()).isEqualByComparingTo("37.5");
    assertThat(h.score()).isEqualTo(38);
    assertThat(h.cashFlow().essential()).isEqualByComparingTo("78000");
    assertThat(h.cashFlow().surplus()).isEqualByComparingTo("22000");
  }

  @Test
  void termNeedAndGapPerEarner() {
    Health h = MoneyHealthService.run(household(ALL), CFG);
    PersonCover me = cover(h, "me");
    assertThat(me.method()).isEqualTo("MULTIPLE");
    assertThat(me.multiple()).isEqualByComparingTo("15");
    assertThat(me.termNeed()).isEqualByComparingTo("32200000");
    assertThat(me.termCover()).isEqualByComparingTo("5000000");
    assertThat(me.termGap()).isEqualByComparingTo("27200000");
    PersonCover spouse = cover(h, "spouse");
    assertThat(spouse.method()).isEqualTo("MULTIPLE");
    assertThat(spouse.termNeed()).isEqualByComparingTo("13500000");
    assertThat(spouse.termGap()).isEqualByComparingTo("13500000");
    assertThat(cover(h, "child").needsTerm()).isFalse();
    // Two term gaps and no personal health cover: three gaps.
    assertThat(h.protection().gaps()).extracting(g -> g.kind()).containsExactly("term", "term", "health");
    assertThat(me.employerHealthCover()).isEqualByComparingTo("500000");
    assertThat(me.personalHealthCover()).isEqualByComparingTo("0");
  }

  @Test
  void topThreeActionsInOrder() {
    Household hh = household(ALL);
    Health h = MoneyHealthService.run(hh, CFG);
    List<Action> actions = ActionPlanService.plan(hh, h, CFG);
    List<Action> top = actions.subList(0, 3);

    assertThat(top).extracting(Action::key).containsExactly(
        ActionPlanService.HIGH_DEBT, ActionPlanService.PROTECT, ActionPlanService.EMERGENCY_FULL);
    assertThat(top).extracting(Action::layer).containsOnly("survival");

    Action debt = top.get(0);
    assertThat(debt.title()).isEqualTo("Repay the ₹1.5 lakh personal loan at 14%");
    assertThat(debt.impact()).isEqualByComparingTo("20");
    assertThat(debt.details().get("monthlyExtra")).isEqualTo(new BigDecimal("22000"));
    assertThat(debt.details().get("months")).isEqualTo(6);
    assertThat(debt.reason()).contains("₹22,000 unallocated monthly surplus")
        .contains("₹8,000 EMI").contains("about 6 months");

    Action protect = top.get(1);
    assertThat(protect.impact()).isEqualByComparingTo("15");
    assertThat(protect.reason()).contains("₹2.72 crore for Me").contains("₹1.35 crore for Spouse")
        .contains("family floater of at least ₹10 lakh with a super top-up");

    Action emergency = top.get(2);
    assertThat(emergency.impact()).isEqualByComparingTo("10");
    assertThat(emergency.details().get("target")).isEqualTo(new BigDecimal("468000"));
    assertThat(emergency.details().get("gap")).isEqualTo(new BigDecimal("218000"));
    assertThat(emergency.details().get("targetAfterDebt")).isEqualTo(new BigDecimal("420000"));
    assertThat(emergency.reason()).contains("after the loan is repaid");
  }

  @Test
  void noSipIncreaseWhileASurvivalCheckIsRed() {
    Household hh = household(ALL);
    List<Action> actions = ActionPlanService.plan(hh, MoneyHealthService.run(hh, CFG), CFG);
    assertThat(actions).extracting(Action::key).doesNotContain("savings.raise", "goals.fund");
    assertThat(actions).extracting(Action::layer).doesNotContain("important", "aspirational");
  }

  @Test
  void emergencyFundIsNeverTheSourceOfTheLoanRepayment() {
    Household hh = household(ALL);
    Action debt = ActionPlanService.plan(hh, MoneyHealthService.run(hh, CFG), CFG).get(0);
    assertThat(debt.details().get("fundingSource")).isEqualTo("monthly_surplus");
    assertThat(debt.reason()).contains("The emergency fund is not used for this.");
  }

  @Test
  void skippedInsuranceMakesProtectionUnknownAndRescalesOver85() {
    Set<String> known = new HashSet<>(ALL);
    known.remove(Household.INSURANCE);
    Household hh = household(Set.copyOf(known));
    Household noPolicies = new Household(hh.asOf(), hh.people(), hh.incomes(), hh.budget(),
        hh.loans(), List.of(), hh.assets(), hh.loansGiven(), hh.accounts(), hh.willRecorded(),
        hh.importantGoalsFundedPct(), hh.knownAreas());
    Health h = MoneyHealthService.run(noPolicies, CFG);
    assertThat(h.check("protection").status()).isEqualTo("unknown");
    assertThat(h.rawScore()).isEqualByComparingTo("44.1");
    assertThat(h.score()).isEqualTo(44);
  }

  @Test
  void everythingSkippedGivesUnknownChecksAndNoRedTile() {
    Household empty = new Household(AS_OF, List.of(new Person("me", "Me", "self", null, false, false)),
        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), false, null, Set.of());
    Health h = MoneyHealthService.run(empty, CFG);
    assertThat(h.checks()).extracting(MoneyHealthService.Check::status).containsOnly("unknown");
    assertThat(h.score()).isNull();
  }

  private static PersonCover cover(Health h, String id) {
    return h.protection().perPerson().stream().filter(c -> c.personId().equals(id)).findFirst().orElseThrow();
  }

  private static void assertCheck(Health h, String key, String shown, String status, String points) {
    var c = h.check(key);
    assertThat(c.shown()).as(key).isEqualByComparingTo(shown);
    assertThat(c.status()).as(key).isEqualTo(status);
    assertThat(c.points()).as(key).isEqualByComparingTo(points);
  }

  static BigDecimal bd(double v) { return BigDecimal.valueOf(v); }
}
