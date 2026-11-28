package com.jizza.platform.usecase.vita.insurance;

import static org.assertj.core.api.Assertions.assertThat;

import com.jizza.platform.usecase.vita.medicine.ReminderSettings;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Fixture F (Vita spec 10): a family floater of ₹10,00,000 renewing on 2027-03-31, and Father's
 * settled claim for a stay on 2-4 Aug 2026. The REST side, with Kai's Protection view, is
 * VitaInsuranceE2eTest.
 */
class FixtureFTest {

  private static final LocalDate RENEWS = LocalDate.of(2027, 3, 31);
  private static final BigDecimal SUM = new BigDecimal("1000000");

  @Test
  void thePolicyYearIsTheTwelveMonthsEndingOnTheRenewalDate() {
    PolicyYear y = PolicyYear.endingOn(RENEWS);
    assertThat(y.from()).isEqualTo(LocalDate.of(2026, 4, 1));
    assertThat(y.to()).isEqualTo(RENEWS);
    assertThat(y.contains(LocalDate.of(2026, 8, 2))).isTrue();
    assertThat(y.contains(LocalDate.of(2027, 4, 2))).isFalse();
    assertThat(PolicyYear.containing(RENEWS, LocalDate.of(2027, 4, 2)).to()).isEqualTo(LocalDate.of(2028, 3, 31));
    assertThat(PolicyYear.containing(RENEWS, LocalDate.of(2025, 5, 1)).to()).isEqualTo(LocalDate.of(2026, 3, 31));
    assertThat(PolicyYear.endingOn(null)).isNull();
  }

  @Test
  void outOfPocketAndSumLeft() {
    assertThat(PolicyYear.outOfPocket("settled", new BigDecimal("120000"), new BigDecimal("105000")))
        .isEqualByComparingTo("15000");
    assertThat(PolicyYear.left(SUM, new BigDecimal("105000"))).isEqualByComparingTo("895000");
    assertThat(PolicyYear.outOfPocket("rejected", new BigDecimal("120000"), null)).isEqualByComparingTo("120000");
    assertThat(PolicyYear.outOfPocket("filed", new BigDecimal("120000"), null)).isNull();
    assertThat(PolicyYear.left(SUM, new BigDecimal("1200000"))).isEqualByComparingTo("0");
  }

  @Test
  void theRenewalReminderIsThirtyDaysBefore() {
    ReminderSettings rs = new ReminderSettings(60, 15, LocalTime.of(20, 0), false, Map.of("policy_renewal", 30));
    assertThat(VitaInsuranceUseCase.reminderOn(RENEWS, rs)).isEqualTo(LocalDate.of(2027, 3, 1));
  }

  @Test
  void theHealthKindsAreVitasAndTermIsKais() {
    assertThat(PolicyYear.HEALTH_KINDS).containsExactlyInAnyOrder("health", "super_topup", "critical_illness",
        "personal_accident", "government_scheme");
    assertThat(PolicyYear.healthKind("term")).isFalse();
    assertThat(VitaInsuranceUseCase.masked("3456")).isEqualTo("•••• 3456");
    assertThat(VitaInsuranceUseCase.masked(null)).isNull();
  }
}
