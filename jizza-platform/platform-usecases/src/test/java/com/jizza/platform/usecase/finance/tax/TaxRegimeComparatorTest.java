package com.jizza.platform.usecase.finance.tax;

import static org.assertj.core.api.Assertions.assertThat;

import com.jizza.platform.usecase.finance.model.KaiDefaults;
import com.jizza.platform.usecase.finance.tax.TaxRegimeComparator.Comparison;
import com.jizza.platform.usecase.finance.tax.TaxRegimeComparator.Input;
import com.jizza.platform.usecase.finance.tax.TaxRegimeComparator.Rules;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The regime comparison on the shipped (unverified) FY 2026-27 figures. */
class TaxRegimeComparatorTest {

  static final Rules RULES;

  static {
    var v = KaiDefaults.values(List.of("tax.new_regime", "tax.old_regime", "tax.cess"));
    RULES = Rules.from(v.get("tax.new_regime"), v.get("tax.old_regime"), v.get("tax.cess"), true);
  }

  static Input salary(long gross, Map<String, BigDecimal> deductions) {
    return new Input(BigDecimal.valueOf(gross), true, 34, false, deductions, null, null, null, null);
  }

  @Test
  void newRegimeOnFixtureDsSalaryAndOldRegimeWithDeductions() {
    // Gross ₹19.2 lakh: taxable 18.45 lakh after ₹75,000; 20,000 + 40,000 + 60,000 + 49,000 = 1,69,000;
    // cess 4% 6,760. Old: 19.2 lakh − 50,000 − 1,50,000 (80C) − 25,000 (80D) = 16.95 lakh;
    // 12,500 + 1,00,000 + 2,08,500 = 3,21,000; cess 12,840.
    Comparison c = TaxRegimeComparator.compare(salary(1_920_000,
        Map.of("section80C", bd(200_000), "section80DSelf", bd(25_000))), RULES);
    assertThat(c.newRegime().taxableIncome()).isEqualByComparingTo("1845000");
    assertThat(c.newRegime().slabTax()).isEqualByComparingTo("169000");
    assertThat(c.newRegime().total()).isEqualByComparingTo("175760");
    assertThat(c.oldRegime().taxableIncome()).isEqualByComparingTo("1695000");
    assertThat(c.oldRegime().total()).isEqualByComparingTo("333840");
    assertThat(c.cheaper()).isEqualTo("new");
    assertThat(c.gap()).isEqualByComparingTo("158080");
    assertThat(c.needsVerification()).isTrue();
    assertThat(c.oldRegime().steps()).anySatisfy(s -> assertThat(s.label()).contains("limit ₹1,50,000"));
  }

  @Test
  void theRebateMakesTwelveLakhTaxableTaxFree() {
    Comparison c = TaxRegimeComparator.compare(salary(1_275_000, Map.of()), RULES);
    assertThat(c.newRegime().taxableIncome()).isEqualByComparingTo("1200000");
    assertThat(c.newRegime().rebate()).isEqualByComparingTo("60000");
    assertThat(c.newRegime().total()).isEqualByComparingTo("0");
  }

  @Test
  void withoutDeductionsTheOldRegimeSaysNotEnoughDataAndNeverGuesses() {
    Comparison c = TaxRegimeComparator.compare(salary(1_920_000, Map.of()), RULES);
    assertThat(c.oldRegime()).isNull();
    assertThat(c.oldStatus()).isEqualTo("not_enough_data");
    assertThat(c.cheaper()).isNull();
    assertThat(c.notes()).anySatisfy(n -> assertThat(n).startsWith("Old regime: not enough data"));
  }

  @Test
  void justAboveTheRebateLimitItPointsToMarginalRelief() {
    Comparison c = TaxRegimeComparator.compare(salary(1_285_000, Map.of()), RULES);
    assertThat(c.notes()).anySatisfy(n -> assertThat(n).contains("marginal relief"));
  }

  @Test
  void hraUsesTheLeastOfTheThreeLimits() {
    // Basic 6 lakh, HRA 2.4 lakh, rent 2.4 lakh in a metro: least of 2.4 lakh, 2.4 − 0.6 = 1.8 lakh,
    // 50% of basic = 3 lakh → 1.8 lakh exempt.
    var in = new Input(bd(1_200_000), true, 34, false, Map.of(), bd(600_000), bd(240_000), bd(240_000), true);
    Comparison c = TaxRegimeComparator.compare(in, RULES);
    assertThat(c.oldRegime().steps()).anySatisfy(s -> {
      assertThat(s.label()).isEqualTo("HRA exemption");
      assertThat(s.amount()).isEqualByComparingTo("-180000");
    });
  }

  @Test
  void noGrossIncomeMeansNotEnoughData() {
    Comparison c = TaxRegimeComparator.compare(new Input(null, true, 34, false, Map.of(), null, null, null, null), RULES);
    assertThat(c.status()).isEqualTo("not_enough_data");
  }

  static BigDecimal bd(double v) {
    return BigDecimal.valueOf(v);
  }
}
