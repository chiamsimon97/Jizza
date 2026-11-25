package com.jizza.platform.usecase.finance.tax;

import static com.jizza.platform.usecase.finance.model.Inr.HUNDRED;
import static com.jizza.platform.usecase.finance.model.Inr.nz;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.platform.usecase.finance.model.Inr;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Old against new tax regime for one person and one financial year (spec 5.10). Every slab, rate
 * and limit comes from {@code fin_config} ({@code tax.new_regime}, {@code tax.old_regime},
 * {@code tax.cess}); none lives here. A result is marked {@code needsVerification} when any figure
 * behind it is unverified.
 *
 * <p>The old regime is worked out only from deductions the user entered; with none it says "Old
 * regime: not enough data" and never guesses. Not modelled, and said so: surcharge, marginal
 * relief, losses carried forward, business income, and the slabs and limits for age 60 and over.
 */
public final class TaxRegimeComparator {

  private TaxRegimeComparator() {}

  /** One slab: up to {@code upTo} (null for the top slab) at {@code ratePct}. */
  public record Slab(BigDecimal upTo, BigDecimal ratePct) {}

  public record Rebate(BigDecimal maxRebate, BigDecimal taxableIncomeUpTo) {}

  public record Regime(String financialYear, List<Slab> slabs, BigDecimal standardDeduction,
                       Rebate rebate, Map<String, BigDecimal> limits, Map<String, BigDecimal> hra) {}

  public record Rules(Regime newRegime, Regime oldRegime, BigDecimal cessPct,
                      BigDecimal surchargeAboveIncome, boolean needsVerification) {

    public static Rules from(JsonNode newRegime, JsonNode oldRegime, JsonNode cess, boolean verify) {
      return new Rules(regime(newRegime), regime(oldRegime), cess.get("healthAndEducationPct").decimalValue(),
          cess.path("surchargeAboveIncome").decimalValue(), verify);
    }

    private static Regime regime(JsonNode n) {
      List<Slab> slabs = new ArrayList<>();
      for (JsonNode s : n.get("slabs")) {
        JsonNode upTo = s.get("upTo");
        slabs.add(new Slab(upTo == null || upTo.isNull() ? null : upTo.decimalValue(), s.get("ratePct").decimalValue()));
      }
      JsonNode r = n.get("rebate87A");
      Map<String, BigDecimal> limits = new LinkedHashMap<>();
      n.path("limits").fields().forEachRemaining(e -> limits.put(e.getKey(), e.getValue().decimalValue()));
      Map<String, BigDecimal> hra = new LinkedHashMap<>();
      n.path("hra").fields().forEachRemaining(e -> hra.put(e.getKey(), e.getValue().decimalValue()));
      return new Regime(n.path("financialYear").asText(), slabs, n.get("standardDeduction").decimalValue(),
          r == null ? null : new Rebate(r.get("maxRebate").decimalValue(), r.get("taxableIncomeUpTo").decimalValue()),
          limits, hra);
    }
  }

  /**
   * One person's inputs, yearly rupees. Deductions are what the person can claim as they typed
   * them; Kai applies the limits.
   *
   * @param salaried whether the income is salary or pension (the standard deduction applies)
   * @param deductions section80C, section80CCD1B, section80DSelf, section80DParents,
   *     homeLoanInterest; an empty map means none were entered
   */
  public record Input(BigDecimal grossAnnual, boolean salaried, Integer age, boolean parentsSenior,
                      Map<String, BigDecimal> deductions, BigDecimal basicAnnual,
                      BigDecimal hraAnnual, BigDecimal rentAnnual, Boolean metro) {}

  /** One line of the working, for the screen: "Standard deduction −₹75,000". */
  public record Step(String label, BigDecimal amount) {}

  public record RegimeResult(BigDecimal taxableIncome, BigDecimal slabTax, BigDecimal rebate,
                             BigDecimal cess, BigDecimal total, List<Step> steps) {}

  /**
   * @param oldRegime null when there is not enough data
   * @param cheaper new, old, same, or null when only one could be worked out
   */
  public record Comparison(String status, RegimeResult newRegime, RegimeResult oldRegime,
                           String oldStatus, String cheaper, BigDecimal gap, boolean needsVerification,
                           List<String> notes) {}

  public static final List<String> DEDUCTION_KEYS = List.of("section80C", "section80CCD1B",
      "section80DSelf", "section80DParents", "homeLoanInterest");

  public static Comparison compare(Input in, Rules rules) {
    List<String> notes = new ArrayList<>();
    if (in.grossAnnual() == null || in.grossAnnual().signum() <= 0) {
      return new Comparison("not_enough_data", null, null, "not_enough_data", null, null,
          rules.needsVerification(), List.of("Add this person's gross income to compare the regimes."));
    }
    BigDecimal gross = in.grossAnnual();
    if (in.age() != null && in.age() >= 60) {
      notes.add("Different slabs and limits apply from age 60. Kai does not model them yet: ask your CA.");
    }
    if (gross.compareTo(nz(rules.surchargeAboveIncome())) > 0 && rules.surchargeAboveIncome() != null) {
      notes.add("Income above " + Inr.words(rules.surchargeAboveIncome()) + " attracts surcharge, which Kai does not model: ask your CA.");
    }

    // New regime: standard deduction only.
    Regime nr = rules.newRegime();
    List<Step> nSteps = new ArrayList<>();
    nSteps.add(new Step("Gross income", gross));
    BigDecimal nTaxable = gross;
    if (in.salaried()) {
      BigDecimal sd = nr.standardDeduction().min(nTaxable);
      nTaxable = nTaxable.subtract(sd);
      nSteps.add(new Step("Standard deduction", sd.negate()));
    }
    RegimeResult newResult = tax(nTaxable, nr, rules.cessPct(), nSteps);
    // Marginal relief can apply when the tax is more than the income above the rebate limit.
    if (nr.rebate() != null && nTaxable.compareTo(nr.rebate().taxableIncomeUpTo()) > 0
        && newResult.slabTax().compareTo(nTaxable.subtract(nr.rebate().taxableIncomeUpTo())) > 0) {
      notes.add("Just above the rebate limit, marginal relief can lower the new-regime tax. Kai does not model it: ask your CA.");
    }

    // Old regime: only from what the user entered.
    Map<String, BigDecimal> d = in.deductions() == null ? Map.of() : in.deductions();
    boolean anyDeduction = d.values().stream().anyMatch(v -> v != null && v.signum() > 0);
    boolean hraKnown = in.rentAnnual() != null && in.rentAnnual().signum() > 0 && in.basicAnnual() != null
        && in.hraAnnual() != null && in.metro() != null;
    RegimeResult oldResult = null;
    String oldStatus = "not_enough_data";
    if (anyDeduction || hraKnown || (in.deductions() != null && !in.deductions().isEmpty())) {
      Regime or = rules.oldRegime();
      List<Step> oSteps = new ArrayList<>();
      oSteps.add(new Step("Gross income", gross));
      BigDecimal taxable = gross;
      if (in.salaried()) {
        BigDecimal sd = or.standardDeduction().min(taxable);
        taxable = taxable.subtract(sd);
        oSteps.add(new Step("Standard deduction", sd.negate()));
      }
      if (hraKnown) {
        BigDecimal pctOfBasic = Boolean.TRUE.equals(in.metro()) ? or.hra().get("metroPctOfBasic") : or.hra().get("otherPctOfBasic");
        BigDecimal rentOver = in.rentAnnual().subtract(Inr.div(in.basicAnnual().multiply(or.hra().get("rentAbovePctOfBasic")), HUNDRED));
        BigDecimal cap = Inr.div(in.basicAnnual().multiply(pctOfBasic), HUNDRED);
        BigDecimal exempt = Inr.rupees(in.hraAnnual().min(rentOver).min(cap).max(BigDecimal.ZERO)).min(taxable);
        taxable = taxable.subtract(exempt);
        oSteps.add(new Step("HRA exemption", exempt.negate()));
      }
      taxable = deduct(taxable, d, "section80C", or.limits().get("section80C"), "Section 80C", oSteps);
      taxable = deduct(taxable, d, "section80CCD1B", or.limits().get("section80CCD1B"), "NPS, section 80CCD(1B)", oSteps);
      taxable = deduct(taxable, d, "section80DSelf", or.limits().get("section80DSelf"), "Health insurance, section 80D (self and family)", oSteps);
      taxable = deduct(taxable, d, "section80DParents",
          in.parentsSenior() ? or.limits().get("section80DParentsSenior") : or.limits().get("section80DParents"),
          "Health insurance, section 80D (parents)", oSteps);
      taxable = deduct(taxable, d, "homeLoanInterest", or.limits().get("homeLoanInterest"), "Home-loan interest", oSteps);
      oldResult = tax(taxable, or, rules.cessPct(), oSteps);
      oldStatus = "ok";
    } else {
      notes.add("Old regime: not enough data. Enter your deductions (80C, 80D, HRA, home-loan interest) to compare.");
    }

    String cheaper = null;
    BigDecimal gap = null;
    if (oldResult != null) {
      int c = newResult.total().compareTo(oldResult.total());
      cheaper = c < 0 ? "new" : c > 0 ? "old" : "same";
      gap = newResult.total().subtract(oldResult.total()).abs();
    }
    return new Comparison("ok", newResult, oldResult, oldStatus, cheaper, gap, rules.needsVerification(), notes);
  }

  private static BigDecimal deduct(BigDecimal taxable, Map<String, BigDecimal> d, String key, BigDecimal limit,
                                   String label, List<Step> steps) {
    BigDecimal v = d.get(key);
    if (v == null || v.signum() <= 0) return taxable;
    BigDecimal allowed = (limit == null ? v : v.min(limit)).min(taxable);
    steps.add(new Step(label + (limit != null && v.compareTo(limit) > 0 ? " (limit " + Inr.group(limit) + ")" : ""),
        allowed.negate()));
    return taxable.subtract(allowed);
  }

  static RegimeResult tax(BigDecimal taxable, Regime r, BigDecimal cessPct, List<Step> steps) {
    taxable = taxable.max(BigDecimal.ZERO);
    steps.add(new Step("Taxable income", taxable));
    BigDecimal tax = BigDecimal.ZERO;
    BigDecimal from = BigDecimal.ZERO;
    for (Slab s : r.slabs()) {
      if (taxable.compareTo(from) <= 0) break;
      BigDecimal top = s.upTo() == null ? taxable : s.upTo().min(taxable);
      BigDecimal slice = top.subtract(from);
      if (slice.signum() > 0 && s.ratePct().signum() > 0) {
        BigDecimal t = Inr.div(slice.multiply(s.ratePct()), HUNDRED);
        tax = tax.add(t);
        steps.add(new Step(Inr.group(from) + " to " + (s.upTo() == null ? "above" : Inr.group(s.upTo()))
            + " at " + Inr.pctWords(s.ratePct()), Inr.rupees(t)));
      }
      if (s.upTo() == null) break;
      from = s.upTo();
    }
    tax = Inr.rupees(tax);
    BigDecimal rebate = BigDecimal.ZERO;
    if (r.rebate() != null && taxable.compareTo(r.rebate().taxableIncomeUpTo()) <= 0) {
      rebate = tax.min(r.rebate().maxRebate());
      if (rebate.signum() > 0) steps.add(new Step("Rebate, section 87A", rebate.negate()));
    }
    BigDecimal after = tax.subtract(rebate);
    BigDecimal cess = after.multiply(cessPct).divide(HUNDRED, 0, RoundingMode.HALF_UP);
    if (cess.signum() > 0) steps.add(new Step("Health and education cess " + Inr.pctWords(cessPct), cess));
    BigDecimal total = after.add(cess);
    steps.add(new Step("Tax for the year", total));
    return new RegimeResult(taxable, tax, rebate, cess, total, List.copyOf(steps));
  }
}
