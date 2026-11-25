package com.jizza.platform.usecase.finance.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jizza.engine.api.error.ValidationException;
import com.jizza.platform.usecase.finance.risk.RiskAssessment.Facts;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class RiskAssessmentTest {

  @Test
  void theProfileIsTheLowerOfCapacityAndToleranceAndTheConflictIsShown() {
    // Tolerance 3 × 6 = 18: Aggressive. Capacity: one stable earner 2, one dependant 2, 3.2 months 2,
    // 16.2% 3, 15 years 3 = 12: Moderate.
    var facts = new Facts(1, true, 1, BigDecimal.valueOf(3.2), BigDecimal.valueOf(16.2), BigDecimal.valueOf(15));
    var r = RiskAssessment.assess(List.of(3, 3, 3, 3, 3, 3), facts);
    assertThat(r.tolerance()).isEqualTo("aggressive");
    assertThat(r.capacityScore()).isEqualTo(12);
    assertThat(r.capacity()).isEqualTo("moderate");
    assertThat(r.profile()).isEqualTo("moderate");
    assertThat(r.conflict()).isTrue();
    assertThat(r.decidedBy()).isEqualTo("capacity");
    assertThat(r.explanation()).contains("Kai uses the lower: Moderate");
  }

  @Test
  void bandsFollowTheQuestionnaire() {
    var facts = new Facts(2, true, 0, BigDecimal.valueOf(7), BigDecimal.valueOf(10), BigDecimal.valueOf(10));
    assertThat(RiskAssessment.assess(List.of(1, 1, 1, 2, 2, 2), facts).tolerance()).isEqualTo("conservative"); // 9
    assertThat(RiskAssessment.assess(List.of(2, 2, 2, 2, 1, 1), facts).tolerance()).isEqualTo("moderate"); // 10
    assertThat(RiskAssessment.assess(List.of(3, 3, 3, 2, 2, 2), facts).tolerance()).isEqualTo("aggressive"); // 15
    assertThat(RiskAssessment.assess(List.of(3, 3, 3, 3, 3, 3), facts).capacity()).isEqualTo("aggressive"); // 15
  }

  @Test
  void missingFactsCountAsTheMiddleAnswerAndSaySo() {
    var r = RiskAssessment.assess(List.of(2, 2, 2, 2, 2, 2), new Facts(null, false, null, null, null, null));
    assertThat(r.capacityScore()).isEqualTo(10);
    assertThat(r.facts()).allSatisfy(f -> assertThat(f.assumed()).isTrue());
  }

  @Test
  void answersMustBeSixScoresOfOneToThree() {
    assertThatThrownBy(() -> RiskAssessment.assess(List.of(1, 2, 3), null)).isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> RiskAssessment.assess(List.of(1, 2, 3, 4, 1, 1), null)).isInstanceOf(ValidationException.class);
  }
}
