package com.jizza.platform.usecase.finance.records;

import static com.jizza.platform.usecase.finance.records.FieldSpec.integer;
import static com.jizza.platform.usecase.finance.records.FieldSpec.of;
import static com.jizza.platform.usecase.finance.records.FieldSpec.oneOf;
import static com.jizza.platform.usecase.finance.records.FieldSpec.text;

import com.jizza.platform.usecase.finance.records.FieldSpec.Type;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The Phase 1a forms: which table each writes and the fields it may set. */
public enum FinTable {

  ACCOUNT("fin_account", "Account", List.of(
      of("personId", Type.PERSON), of("isJoint", Type.BOOL), of("ownerSharePct", Type.PCT),
      text("jointLabel", 80),
      oneOf("kind", "savings", "current", "credit_card", "demat", "mf_folio", "retirement",
          "deposit", "wallet", "cash", "us_broker", "other").need(),
      text("provider", 60), text("nickname", 80), text("last4", 4), text("currency", 3),
      of("countInNetWorth", Type.BOOL),
      oneOf("nomineeStatus", "nominated", "opted_out", "missing", "unknown"),
      of("isEmergency", Type.BOOL), oneOf("status", "active", "closed"))),

  ASSET("fin_asset", "Asset", List.of(
      of("personId", Type.PERSON), of("accountId", Type.ACCOUNT),
      oneOf("kind", "savings_balance", "fd", "rd", "epf", "ppf", "nps", "ssy", "post_office",
          "gold_physical", "gold_digital", "property", "policy_surrender", "chit", "cash",
          "vehicle", "other").need(),
      text("name", 120), oneOf("assetClass", "cash", "debt", "equity", "gold", "other"),
      text("subCategory", 40), of("value", Type.MONEY).need(), of("valueAsOf", Type.DATE),
      of("rate", Type.RATE), of("maturityDate", Type.DATE), of("grams", Type.DECIMAL),
      integer("purityKarat", 24), of("isEmergency", Type.BOOL), of("isEstimate", Type.BOOL),
      of("pledged", Type.BOOL))),

  LIABILITY("fin_liability", "Loan", List.of(
      of("personId", Type.PERSON), of("isJoint", Type.BOOL), of("ownerSharePct", Type.PCT),
      text("jointLabel", 80),
      oneOf("kind", "HOME", "VEHICLE", "EDUCATION", "PERSONAL", "CREDIT_CARD", "CONSUMER_EMI",
          "GOLD_LOAN", "LOAN_AGAINST_SECURITIES", "LOAN_AGAINST_FD", "LOAN_AGAINST_INSURANCE",
          "PPF_LOAN", "OVERDRAFT", "BUSINESS", "EMPLOYER_ADVANCE", "FAMILY_FRIEND", "MONEYLENDER",
          "CHIT_PRIZE", "OTHER").need(),
      oneOf("lenderType", "bank", "nbfc", "card_issuer", "employer", "family", "friend",
          "moneylender", "chit_fund", "other"),
      text("lenderName", 120), of("isPrivate", Type.BOOL), of("principalTaken", Type.MONEY),
      of("outstanding", Type.MONEY).need(), text("rateInput", 80),
      of("effectiveAnnualRate", Type.RATE),
      oneOf("rateType", "fixed", "floating", "flat", "zero", "informal"),
      oneOf("repaymentMode", "emi", "interest_only", "bullet", "revolving", "flexible"),
      of("emiOrInterest", Type.MONEY), of("startDate", Type.DATE),
      of("maturityOrPromisedDate", Type.DATE), of("prepaymentPenaltyPct", Type.PCT),
      oneOf("taxBenefit", "none", "section_24b", "section_80c", "section_80e", "both", "other"),
      oneOf("collateralType", "none", "property", "vehicle", "gold", "silver", "securities", "fd",
          "policy", "other"),
      oneOf("status", "active", "closed"))),

  PLEDGE("fin_pledge", "Pledge", List.of(
      oneOf("assetKind", "gold", "silver", "securities", "fd", "policy", "property").need(),
      of("assetId", Type.ASSET), of("units", Type.DECIMAL), of("grams", Type.DECIMAL),
      integer("purityKarat", 24), of("valuation", Type.MONEY), of("valuedOn", Type.DATE))),

  LOAN_PAYMENT("fin_loan_payment", "Payment", List.of(
      oneOf("kind", "actual", "promised"), of("dueOrPaidOn", Type.DATE).need(),
      of("amount", Type.MONEY).need(), of("principalPart", Type.MONEY),
      of("interestPart", Type.MONEY))),

  LOAN_GIVEN("fin_loan_given", "Money lent", List.of(
      of("personId", Type.PERSON), text("borrowerName", 120).need(), of("isPrivate", Type.BOOL),
      of("amount", Type.MONEY).need(), of("rate", Type.RATE), of("givenOn", Type.DATE),
      of("expectedBackOn", Type.DATE),
      oneOf("status", "on_track", "overdue", "doubtful", "repaid"))),

  POLICY("fin_insurance_policy", "Policy", List.of(
      of("personId", Type.PERSON),
      oneOf("kind", "term", "health", "super_topup", "personal_accident", "critical_illness",
          "vehicle", "home", "endowment", "ulip", "government_scheme").need(),
      text("providerName", 120), of("sumAssured", Type.MONEY).need(), of("deductible", Type.MONEY),
      of("premium", Type.MONEY), oneOf("premiumFrequency", "monthly", "quarterly", "yearly", "single"),
      of("renewalDate", Type.DATE), of("policyEndDate", Type.DATE),
      of("employerProvided", Type.BOOL), of("coveredPersonIds", Type.PERSONS),
      of("surrenderValue", Type.MONEY), of("documentFileId", Type.FILE))),

  ESTATE("fin_estate_record", "Will", List.of(
      oneOf("willStatus", "none", "draft", "registered"), of("willDate", Type.DATE),
      text("executor", 120), text("notes", 2000), of("documentFileId", Type.FILE))),

  INCOME("fin_income_source", "Income", List.of(
      of("personId", Type.PERSON).need(),
      oneOf("kind", "salary", "business", "rent", "pension", "interest", "other"),
      of("grossMonthly", Type.MONEY), of("takeHomeMonthly", Type.MONEY).need(),
      of("basicMonthly", Type.MONEY), of("hraMonthly", Type.MONEY),
      oneOf("stability", "stable", "variable"), of("nextHikeMonth", Type.DATE))),

  BUDGET("fin_expense_budget", "Budget line", List.of(
      of("categoryId", Type.CATEGORY).need(), of("monthlyAmount", Type.MONEY).need())),

  /** Phase 1b: a goal ({@code fin_plan}). Until Phase 2 and 3 it records what is saved and what goes in. */
  GOAL("fin_plan", "Goal", List.of(
      oneOf("type", "emergency", "retirement", "education", "home", "car", "travel", "wedding",
          "wealth", "custom").need(),
      text("name", 80).need(), of("ownerPersonId", Type.PERSON), of("targetDate", Type.DATE).need(),
      of("targetAmountToday", Type.MONEY).need(), oneOf("inflationKey", "cpi", "education", "car", "none"),
      oneOf("priorityTier", "survival", "important", "aspirational"),
      oneOf("riskProfile", "conservative", "moderate", "aggressive"),
      oneOf("status", "draft", "active", "paused", "achieved", "archived"),
      of("currentValue", Type.MONEY), of("monthlyContribution", Type.MONEY), of("stepupPct", Type.PCT),
      text("colour", 16))),

  /** Phase 1b: one person's tax inputs for one financial year. */
  TAX_PROFILE("fin_tax_profile", "Tax profile", List.of(
      oneOf("regime", "new", "old"), of("rentPaidMonthly", Type.MONEY), of("metro", Type.BOOL)));

  public final String table;
  public final String label;
  public final Map<String, FieldSpec> fields;

  FinTable(String table, String label, List<FieldSpec> fields) {
    this.table = table;
    this.label = label;
    Map<String, FieldSpec> m = new LinkedHashMap<>();
    fields.forEach(f -> m.put(f.name(), f));
    this.fields = Map.copyOf(m);
  }
}
