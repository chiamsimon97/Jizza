package com.jizza.platform.usecase.finance.cashflow;

import static com.jizza.platform.usecase.finance.model.Inr.TWELVE;
import static com.jizza.platform.usecase.finance.model.Inr.div;
import static com.jizza.platform.usecase.finance.model.Inr.nz;

import com.jizza.platform.usecase.finance.model.Household;
import com.jizza.platform.usecase.finance.model.Household.Loan;
import com.jizza.platform.usecase.finance.model.Household.Policy;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Monthly cash flow (5.7): take-home income minus the budget gives the surplus.
 *
 * <p>Essential expenses are the Need budget plus every loan's contractual monthly payment plus
 * one-twelfth of the yearly premiums the household pays itself (employer cover costs nothing).
 * Loan payments always come from the loans, never from a budget line, so they are counted once.
 */
public final class CashFlowService {

  private CashFlowService() {}

  public record PersonIncome(String personId, BigDecimal grossMonthly, BigDecimal takeHomeMonthly) {}

  /**
   * @param loanPayments contractual monthly loan payments (EMIs, interest, informal instalments)
   * @param debtService loanPayments plus the interest a bullet loan accrues each month, for the
   *     Debt check
   */
  public record CashFlow(List<PersonIncome> income, BigDecimal takeHome, BigDecimal gross,
                         BigDecimal needBudget, BigDecimal loanPayments, BigDecimal debtService,
                         BigDecimal premiumsMonthly, BigDecimal essential, BigDecimal want,
                         BigDecimal save, BigDecimal surplus) {}

  public static CashFlow compute(Household h) {
    List<PersonIncome> income = new ArrayList<>();
    BigDecimal takeHome = BigDecimal.ZERO;
    BigDecimal gross = BigDecimal.ZERO;
    for (var i : h.incomes()) {
      BigDecimal th = nz(i.takeHomeMonthly());
      // Quick start asks for take-home only; without a gross figure, take-home stands in for it.
      BigDecimal g = i.grossMonthly() != null && i.grossMonthly().signum() > 0 ? i.grossMonthly() : th;
      income.add(new PersonIncome(i.personId(), g, th));
      takeHome = takeHome.add(th);
      gross = gross.add(g);
    }
    BigDecimal need = bucket(h, "need");
    BigDecimal want = bucket(h, "want");
    BigDecimal save = bucket(h, "save");
    BigDecimal payments = BigDecimal.ZERO;
    BigDecimal service = BigDecimal.ZERO;
    for (Loan l : h.loans()) {
      BigDecimal p = nz(l.monthlyPayment());
      payments = payments.add(p);
      if (p.signum() == 0 && "bullet".equals(l.mode())) {
        service = service.add(div(nz(l.outstanding()).multiply(nz(l.ratePct())), BigDecimal.valueOf(1200)));
      }
    }
    service = service.add(payments);
    BigDecimal premiums = premiumsMonthly(h.policies(), false);
    BigDecimal essential = need.add(payments).add(premiums);
    BigDecimal surplus = takeHome.subtract(essential).subtract(want).subtract(save);
    return new CashFlow(income, takeHome, gross, need, payments, service, premiums, essential,
        want, save, surplus);
  }

  /** Monthly cost of premiums; employer-paid cover is left out unless asked for. */
  public static BigDecimal premiumsMonthly(List<Policy> policies, boolean includeEmployer) {
    BigDecimal total = BigDecimal.ZERO;
    for (Policy p : policies) {
      if (p.employerProvided() && !includeEmployer) continue;
      total = total.add(yearly(p));
    }
    return div(total, TWELVE);
  }

  /** A premium as a yearly amount: monthly × 12, quarterly × 4; a single premium counts nothing. */
  public static BigDecimal yearly(Policy p) {
    BigDecimal premium = nz(p.premium());
    return switch (p.frequency() == null ? "yearly" : p.frequency()) {
      case "monthly" -> premium.multiply(TWELVE);
      case "quarterly" -> premium.multiply(BigDecimal.valueOf(4));
      case "single" -> BigDecimal.ZERO;
      default -> premium;
    };
  }

  private static BigDecimal bucket(Household h, String bucket) {
    return h.budget().stream().filter(b -> bucket.equals(b.bucket()))
        .map(b -> nz(b.monthly())).reduce(BigDecimal.ZERO, BigDecimal::add);
  }
}
