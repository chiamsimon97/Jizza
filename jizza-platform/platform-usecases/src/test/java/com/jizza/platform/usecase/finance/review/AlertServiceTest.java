package com.jizza.platform.usecase.finance.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.jizza.platform.usecase.finance.model.Household;
import com.jizza.platform.usecase.finance.model.Household.Loan;
import com.jizza.platform.usecase.finance.model.Household.Person;
import com.jizza.platform.usecase.finance.model.Household.Pledge;
import com.jizza.platform.usecase.finance.model.Household.Policy;
import com.jizza.platform.usecase.finance.model.KaiDefaults;
import com.jizza.platform.usecase.finance.profile.FinProfileUseCase;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AlertServiceTest {

  static final LocalDate TODAY = LocalDate.of(2027, 4, 10);

  @Test
  void renewalsMaturitiesMajorityAndLtvAreDue() {
    var h = new Household(TODAY,
        List.of(new Person("me", "Me", "self", null, true, false),
            new Person("kid", "Riya", "child", LocalDate.of(2009, 6, 1), false, true)),
        List.of(), List.of(),
        List.of(new Loan("gold", "me", "GOLD_LOAN", "Gold loan", bd(240_000), bd(9.5), "bullet", null,
            LocalDate.of(2026, 6, 1), LocalDate.of(2027, 5, 31), bd(240_000), false,
            List.of(new Pledge("gold", bd(30), 22, bd(280_000), TODAY)))),
        List.of(new Policy("p", "me", "health", bd(1_000_000), bd(20_000), "yearly", false, List.of(),
            TODAY.plusDays(20))),
        List.of(), List.of(), List.of(), false, null, Set.of());
    var alerts = AlertService.due(h, KaiDefaults.config(), Map.of("regulatory.gold_loan_ltv", true));
    assertThat(alerts).extracting(AlertService.Alert::kind).containsExactlyInAnyOrder(
        AlertService.RENEWAL, AlertService.MATURITY, AlertService.GOLD_LTV, AlertService.MAJORITY,
        AlertService.VERIFY);
    assertThat(alerts).allSatisfy(a -> assertThat(a.body()).doesNotContain("₹"));
  }

  @Test
  void aRenewalVitaRemindsAboutIsNotRaisedAgain() {
    var h = new Household(TODAY, List.of(new Person("me", "Me", "self", null, true, false)),
        List.of(), List.of(), List.of(),
        List.of(new Policy("health1", "me", "health", bd(500_000), bd(12_000), "yearly", false, List.of(),
                TODAY.plusDays(10)),
            new Policy("life1", "me", "term_life", bd(5_000_000), bd(9_000), "yearly", false, List.of(),
                TODAY.plusDays(12))),
        List.of(), List.of(), List.of(), false, null, Set.of());
    var alerts = AlertService.due(h, KaiDefaults.config(), Map.of(), Set.of("health1"));
    assertThat(alerts).filteredOn(a -> a.kind().equals(AlertService.RENEWAL)).extracting(AlertService.Alert::body)
        .containsExactly("A term life policy renews on 22 Apr 2027.");
  }

  @Test
  void everyAlertKindCanBeMutedInSettings() {
    assertThat(new HashSet<>(AlertService.KINDS)).isEqualTo(FinProfileUseCase.ALERT_KINDS);
  }

  static BigDecimal bd(double v) { return BigDecimal.valueOf(v); }
}
