package com.jizza.platform.usecase.finance.protection;

import static com.jizza.platform.usecase.finance.model.Inr.TWELVE;
import static com.jizza.platform.usecase.finance.model.Inr.nz;
import static com.jizza.platform.usecase.finance.model.Inr.pct;
import static com.jizza.platform.usecase.finance.model.Inr.rupees;

import com.jizza.platform.usecase.finance.cashflow.CashFlowService;
import com.jizza.platform.usecase.finance.cashflow.CashFlowService.CashFlow;
import com.jizza.platform.usecase.finance.cashflow.CashFlowService.PersonIncome;
import com.jizza.platform.usecase.finance.model.Household;
import com.jizza.platform.usecase.finance.model.Household.Person;
import com.jizza.platform.usecase.finance.model.Household.Policy;
import com.jizza.platform.usecase.finance.model.KaiConfig;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * The protection calculator (5.9).
 *
 * <p>Term need per earning person, when anyone else depends on the household: income multiple
 * (or the human life value when it is entered and larger) × annual gross; outstanding loans are
 * added, and liquid emergency money taken away, once, for the highest earner. Health: every
 * person of the core family (self, spouse, children) needs personal cover of at least the floor,
 * not tied to an employer; each parent needs their own policy. Premiums above the flag share of
 * income raise a flag, not a gap. Kai never names an insurer.
 */
public final class ProtectionCalculator {

  private ProtectionCalculator() {}

  private static final Set<String> CORE = Set.of("self", "spouse", "child");

  public record PersonCover(String personId, String name, boolean needsTerm, String method,
                            BigDecimal multiple, BigDecimal termNeed, BigDecimal termCover,
                            BigDecimal termGap, BigDecimal personalHealthCover,
                            BigDecimal employerHealthCover, BigDecimal superTopUp) {}

  /** kind is term or health; amount is the cover missing (term) or the floor (health). */
  public record Gap(String kind, List<String> personIds, BigDecimal amount) {}

  public record Protection(List<PersonCover> perPerson, List<Gap> gaps,
                           BigDecimal premiumsYearly, BigDecimal premiumsPctOfIncome,
                           boolean affordabilityFlag, BigDecimal healthFloor) {
    public int gapCount() { return gaps.size(); }
  }

  /**
   * @param liquidEmergency money flagged for emergencies, taken away from the highest earner's need
   * @param humanLifeValues optional HLV per person id (Phase 1a has none entered)
   */
  public static Protection compute(Household h, CashFlow cf, BigDecimal liquidEmergency,
                                   KaiConfig cfg, java.util.Map<String, BigDecimal> humanLifeValues) {
    BigDecimal loans = h.loans().stream().map(l -> nz(l.outstanding()))
        .reduce(BigDecimal.ZERO, BigDecimal::add);
    boolean dependants = h.people().size() > 1;
    String topEarner = cf.income().stream()
        .max(Comparator.comparing(PersonIncome::grossMonthly)).map(PersonIncome::personId).orElse(null);
    BigDecimal floor = cfg.survival().healthCoverFloor();

    List<PersonCover> covers = new ArrayList<>();
    List<Gap> gaps = new ArrayList<>();
    for (Person p : h.people()) {
      BigDecimal annualGross = cf.income().stream().filter(i -> i.personId().equals(p.id()))
          .map(PersonIncome::grossMonthly).reduce(BigDecimal.ZERO, BigDecimal::add).multiply(TWELVE);
      boolean earning = annualGross.signum() > 0;
      boolean needsTerm = earning && dependants;
      Integer age = p.ageOn(h.asOf());
      BigDecimal multiple = age != null && age > cfg.termMultiple().upToAge()
          ? cfg.termMultiple().multipleAfter() : cfg.termMultiple().multipleUpToAge();
      BigDecimal byMultiple = multiple.multiply(annualGross);
      BigDecimal hlv = humanLifeValues == null ? null : humanLifeValues.get(p.id());
      String method = hlv != null && hlv.compareTo(byMultiple) > 0 ? "HLV" : "MULTIPLE";
      BigDecimal need = BigDecimal.ZERO;
      if (needsTerm) {
        need = "HLV".equals(method) ? hlv : byMultiple;
        if (p.id().equals(topEarner)) need = need.add(loans).subtract(nz(liquidEmergency));
        need = rupees(need.max(BigDecimal.ZERO));
      }
      BigDecimal term = sum(h.policies(), p.id(), "term", null);
      BigDecimal termGap = needsTerm ? need.subtract(term).max(BigDecimal.ZERO) : BigDecimal.ZERO;
      if (termGap.signum() > 0) gaps.add(new Gap("term", List.of(p.id()), termGap));
      covers.add(new PersonCover(p.id(), p.name(), needsTerm, method, multiple, need, term,
          termGap, sum(h.policies(), p.id(), "health", false), sum(h.policies(), p.id(), "health", true),
          sum(h.policies(), p.id(), "super_topup", null)));
    }

    List<String> coreShort = covers.stream()
        .filter(c -> CORE.contains(kind(h, c.personId())))
        .filter(c -> c.personalHealthCover().compareTo(floor) < 0)
        .map(PersonCover::personId).toList();
    if (!coreShort.isEmpty()) gaps.add(new Gap("health", coreShort, floor));
    covers.stream().filter(c -> "parent".equals(kind(h, c.personId())))
        .filter(c -> c.personalHealthCover().compareTo(floor) < 0)
        .forEach(c -> gaps.add(new Gap("health", List.of(c.personId()), floor)));

    BigDecimal premiums = h.policies().stream()
        .filter(pol -> Set.of("term", "health", "super_topup").contains(pol.kind()))
        .map(CashFlowService::yearly).reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal annualIncome = cf.gross().multiply(TWELVE);
    BigDecimal premiumPct = annualIncome.signum() > 0 ? pct(premiums, annualIncome) : null;
    BigDecimal flagAt = cfg.check("protection").extra();
    boolean flag = premiumPct != null && flagAt != null && premiumPct.compareTo(flagAt) > 0;
    return new Protection(covers, gaps, rupees(premiums),
        premiumPct == null ? null : premiumPct.setScale(1, java.math.RoundingMode.HALF_UP), flag, floor);
  }

  private static String kind(Household h, String personId) {
    return h.people().stream().filter(p -> p.id().equals(personId)).map(Person::kind)
        .findFirst().orElse("other");
  }

  /** Cover of one kind for a person; employer null means any, true only employer, false only own. */
  private static BigDecimal sum(List<Policy> policies, String personId, String kind, Boolean employer) {
    return policies.stream().filter(p -> kind.equals(p.kind()) && p.covers(personId))
        .filter(p -> employer == null || p.employerProvided() == employer)
        .map(p -> nz(p.sumAssured())).reduce(BigDecimal.ZERO, BigDecimal::add);
  }
}
