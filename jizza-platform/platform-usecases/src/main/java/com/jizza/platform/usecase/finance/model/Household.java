package com.jizza.platform.usecase.finance.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Period;
import java.util.List;
import java.util.Set;

/**
 * Everything the Phase 1a engines read, as plain values: one profile (the user and the people they
 * manage) on one valuation date. Built from the database by the use cases and by hand in the
 * fixture tests, so every engine is a pure function of this record and {@link KaiConfig}.
 *
 * <p>Amounts are the owner's share (a joint loan at 50% carries half its outstanding). Rates are
 * whole percents a year.
 *
 * @param knownAreas the areas the user answered or has data for: {@code income}, {@code spending},
 *     {@code loans}, {@code insurance}, {@code savings}. A check whose area is not known is
 *     "unknown", never red.
 * @param importantGoalsFundedPct weighted funded % of important goals; null until goals exist
 */
public record Household(
    LocalDate asOf,
    List<Person> people,
    List<Income> incomes,
    List<BudgetLine> budget,
    List<Loan> loans,
    List<Policy> policies,
    List<Asset> assets,
    List<LoanGiven> loansGiven,
    List<AccountInfo> accounts,
    boolean willRecorded,
    BigDecimal importantGoalsFundedPct,
    Set<String> knownAreas) {

  public static final String INCOME = "income";
  public static final String SPENDING = "spending";
  public static final String LOANS = "loans";
  public static final String INSURANCE = "insurance";
  public static final String SAVINGS = "savings";

  public boolean knows(String area) { return knownAreas.contains(area); }

  public record Person(String id, String name, String kind, LocalDate dob, boolean earning,
                       boolean dependent) {
    public Integer ageOn(LocalDate date) {
      return dob == null ? null : Period.between(dob, date).getYears();
    }
  }

  public record Income(String personId, BigDecimal grossMonthly, BigDecimal takeHomeMonthly) {}

  /** A budget line: bucket is need, want or save. */
  public record BudgetLine(String category, String bucket, BigDecimal monthly) {}

  /**
   * A loan or borrowing.
   *
   * @param mode emi, interest_only, bullet, revolving or flexible
   * @param monthlyPayment the contractual monthly amount (EMI, interest, card minimum, informal
   *     instalment); null or zero when nothing is due each month
   * @param dueDate the maturity or promised date of a date-bound amount
   */
  public record Loan(String id, String personId, String kind, String label, BigDecimal outstanding,
                     BigDecimal ratePct, String mode, BigDecimal monthlyPayment,
                     LocalDate startDate, LocalDate dueDate, BigDecimal principalTaken,
                     boolean privateLender, List<Pledge> pledges) {
    public Loan {
      pledges = pledges == null ? List.of() : List.copyOf(pledges);
    }
  }

  public record Pledge(String assetKind, BigDecimal grams, Integer purityKarat,
                       BigDecimal valuation, LocalDate valuedOn) {}

  /** frequency: monthly, quarterly, yearly or single. */
  public record Policy(String id, String personId, String kind, BigDecimal sumAssured,
                       BigDecimal premium, String frequency, boolean employerProvided,
                       List<String> coveredPersonIds, LocalDate renewalDate) {
    public Policy {
      coveredPersonIds = coveredPersonIds == null ? List.of() : List.copyOf(coveredPersonIds);
    }

    /** Whom it covers: the listed people, else the policy holder. */
    public boolean covers(String personId) {
      return coveredPersonIds.isEmpty() ? personId.equals(this.personId)
          : coveredPersonIds.contains(personId);
    }
  }

  public record Asset(String id, String personId, String kind, String assetClass,
                      BigDecimal value, boolean emergency, boolean pledged,
                      boolean countInNetWorth) {}

  /** status: on_track, overdue, doubtful or repaid. */
  public record LoanGiven(String id, String personId, BigDecimal amount, String status) {}

  /** nomineeStatus: nominated, opted_out, missing or unknown. */
  public record AccountInfo(String id, String personId, String nomineeStatus) {}
}
