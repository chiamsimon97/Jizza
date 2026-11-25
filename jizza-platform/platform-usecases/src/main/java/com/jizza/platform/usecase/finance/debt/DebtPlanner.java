package com.jizza.platform.usecase.finance.debt;

import static com.jizza.platform.usecase.finance.model.Inr.HUNDRED;
import static com.jizza.platform.usecase.finance.model.Inr.TWELVE;
import static com.jizza.platform.usecase.finance.model.Inr.div;
import static com.jizza.platform.usecase.finance.model.Inr.nz;
import static com.jizza.platform.usecase.finance.model.Inr.rupees;

import com.jizza.platform.usecase.finance.model.Household.Loan;
import com.jizza.platform.usecase.finance.model.Household.Pledge;
import com.jizza.platform.usecase.finance.model.KaiConfig;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The debt planner (5.13): how each month's surplus is spread over loans, and what is left to
 * invest. Pure: plain records in, plain records out.
 *
 * <p>Each month, starting the month after the valuation date: (1) every loan's contractual payment
 * is already in the budget, so it is not taken from the surplus; (2) revolving card balances and
 * moneylender loans are paid in full, highest rate first, as long as every date-bound amount can
 * still be met from the months left; (3) date-bound amounts (bullet maturities, promised dates),
 * earliest first, each get what is left of them ÷ the months left (the due month included),
 * capped by the cash left; (4) the rest goes to other loans by the prepay-or-invest rule
 * (avalanche, or snowball when chosen) and then to investing.
 *
 * <p>A card balance paid in full by its due date costs nothing; once it is carried into another
 * month, interest of rate ÷ 12 accrues on the whole balance every month until it is cleared.
 */
public final class DebtPlanner {

  private DebtPlanner() {}

  private static final int HORIZON_MONTHS = 600;
  private static final int MIN_ROWS = 12;
  private static final int MAX_ROWS = 60;
  private static final Set<String> PAY_IN_FULL_KINDS = Set.of("CREDIT_CARD", "MONEYLENDER");

  /** The steps of the repayment order. */
  public static final String PAY_IN_FULL = "pay_in_full";
  public static final String DATE_BOUND = "date_bound";
  public static final String PREPAY = "prepay";
  public static final String SPLIT = "split";
  public static final String KEEP_EMI = "keep_emi";

  public record OrderItem(String loanId, String label, String step, BigDecimal ratePct,
                          LocalDate dueDate) {}

  /** One month of the plan: extra payments per loan (beyond contractual), and what is left. */
  public record MonthRow(YearMonth month, Map<String, BigDecimal> payments, BigDecimal investable) {}

  public record LoanOutcome(String loanId, YearMonth debtFreeBy, BigDecimal interestPaid,
                            BigDecimal monthlySetAside) {}

  public record GoldLoan(String loanId, BigDecimal principal, int termDays,
                         BigDecimal maturityAmount, BigDecimal maturityInterest, int accruedDays,
                         BigDecimal accruedInterest, BigDecimal pledgedGrams, BigDecimal valuation,
                         BigDecimal ltvPct, BigDecimal capPct, boolean warn,
                         boolean maturityWithinWarning, String ltvBasis) {}

  /**
   * @param interestSaved interest avoided against the baseline of paying only what is due and
   *     paying revolving balances last
   * @param dateBoundFirst true when a card or moneylender payment had to wait so a date-bound
   *     amount stays reachable
   */
  public record Plan(List<OrderItem> order, List<MonthRow> monthly, List<LoanOutcome> loans,
                     List<GoldLoan> goldLoans, BigDecimal interestSaved, YearMonth debtFreeBy,
                     boolean dateBoundFirst) {

    public LoanOutcome outcome(String loanId) {
      return loans.stream().filter(l -> l.loanId().equals(loanId)).findFirst().orElse(null);
    }

    public MonthRow month(YearMonth m) {
      return monthly.stream().filter(r -> r.month().equals(m)).findFirst().orElse(null);
    }
  }

  public static Plan plan(List<Loan> loans, BigDecimal monthlySurplus, LocalDate asOf,
                          KaiConfig cfg, String method, BigDecimal extraPerMonth) {
    String how = method == null || method.isBlank() ? cfg.prepay().method() : method;
    BigDecimal cash = nz(monthlySurplus).add(nz(extraPerMonth)).max(BigDecimal.ZERO);
    Sim plan = simulate(loans, cash, asOf, cfg, how, false);
    Sim baseline = simulate(loans, cash, asOf, cfg, how, true);
    BigDecimal saved = rupees(baseline.interest.subtract(plan.interest)).max(BigDecimal.ZERO);
    List<GoldLoan> gold = loans.stream().filter(l -> "GOLD_LOAN".equals(l.kind()))
        .map(l -> goldLoan(l, asOf, cfg)).toList();
    return new Plan(order(loans, cfg), plan.rows, plan.outcomes(), gold, saved, plan.debtFreeBy(),
        plan.dateBoundFirst);
  }

  // ── Repayment order ─────────────────────────────────────────────────────────

  static List<OrderItem> order(List<Loan> loans, KaiConfig cfg) {
    List<OrderItem> out = new ArrayList<>();
    loans.stream().filter(DebtPlanner::payInFull).sorted(byRateDesc())
        .forEach(l -> out.add(item(l, PAY_IN_FULL)));
    loans.stream().filter(l -> !payInFull(l) && dateBound(l))
        .sorted(Comparator.comparing(Loan::dueDate))
        .forEach(l -> out.add(item(l, DATE_BOUND)));
    loans.stream().filter(l -> !payInFull(l) && !dateBound(l)).sorted(byRateDesc()).forEach(l -> {
      BigDecimal r = nz(l.ratePct());
      String step = r.compareTo(cfg.prepay().repayAbovePct()) > 0 ? PREPAY
          : r.compareTo(cfg.prepay().splitFromPct()) >= 0 ? SPLIT : KEEP_EMI;
      out.add(item(l, step));
    });
    return out;
  }

  private static OrderItem item(Loan l, String step) {
    return new OrderItem(l.id(), l.label(), step, l.ratePct(), l.dueDate());
  }

  static boolean payInFull(Loan l) {
    return "revolving".equals(l.mode()) || PAY_IN_FULL_KINDS.contains(l.kind());
  }

  static boolean dateBound(Loan l) {
    return l.dueDate() != null && ("bullet".equals(l.mode()) || "flexible".equals(l.mode()));
  }

  private static Comparator<Loan> byRateDesc() {
    return Comparator.comparing((Loan l) -> nz(l.ratePct())).reversed();
  }

  /**
   * The lender as anyone but the record's owner sees it: a private lender (family, friend) is
   * "Family / friend", never their name.
   */
  public static String sharedLabel(Loan l) {
    return l.privateLender() ? "Family / friend" : l.label();
  }

  // ── Gold loans ──────────────────────────────────────────────────────────────

  /** Maturity amount by simple interest over the term, LTV and its cap (all to be verified). */
  public static GoldLoan goldLoan(Loan l, LocalDate asOf, KaiConfig cfg) {
    BigDecimal principal = l.principalTaken() != null ? l.principalTaken() : nz(l.outstanding());
    BigDecimal rate = div(nz(l.ratePct()), HUNDRED);
    int termDays = l.startDate() != null && l.dueDate() != null
        ? (int) ChronoUnit.DAYS.between(l.startDate(), l.dueDate()) : 0;
    int accruedDays = l.startDate() != null
        ? (int) Math.max(0, ChronoUnit.DAYS.between(l.startDate(), asOf)) : 0;
    boolean bullet = "bullet".equals(l.mode());
    BigDecimal maturityInterest = bullet ? rupees(simpleInterest(principal, rate, termDays)) : BigDecimal.ZERO;
    BigDecimal maturity = bullet ? principal.add(maturityInterest) : nz(l.outstanding());
    BigDecimal accrued = rupees(simpleInterest(principal, rate, accruedDays));
    BigDecimal grams = BigDecimal.ZERO;
    BigDecimal valuation = BigDecimal.ZERO;
    for (Pledge p : l.pledges()) {
      grams = grams.add(nz(p.grams()));
      valuation = valuation.add(nz(p.valuation()));
    }
    boolean atMaturity = bullet && "amountRepayableAtMaturity".equals(cfg.goldLtv().bulletLtvBasis());
    BigDecimal basis = atMaturity ? maturity : nz(l.outstanding());
    BigDecimal ltv = valuation.signum() > 0
        ? div(basis.multiply(HUNDRED), valuation).setScale(1, RoundingMode.HALF_UP) : null;
    BigDecimal cap = cfg.goldLtv().tiers().stream()
        .filter(t -> t.upTo() == null || principal.compareTo(t.upTo()) <= 0)
        .findFirst().map(KaiConfig.LtvTier::capPct).orElse(null);
    boolean warn = ltv != null && ltv.compareTo(cfg.goldLtv().warnAtPct()) >= 0;
    boolean soon = l.dueDate() != null
        && !l.dueDate().isAfter(asOf.plusDays(cfg.goldLtv().warnDaysBeforeMaturity()));
    return new GoldLoan(l.id(), principal, termDays, maturity, maturityInterest, accruedDays,
        accrued, grams, valuation, ltv, cap, warn, soon,
        atMaturity ? "amountRepayableAtMaturity" : "outstanding");
  }

  private static BigDecimal simpleInterest(BigDecimal principal, BigDecimal rate, int days) {
    return principal.multiply(rate).multiply(BigDecimal.valueOf(days))
        .divide(BigDecimal.valueOf(365), 10, RoundingMode.HALF_UP);
  }

  /** What a date-bound loan needs by its due date: the maturity amount of a bullet loan. */
  static BigDecimal dateBoundTarget(Loan l, LocalDate asOf, KaiConfig cfg) {
    if ("bullet".equals(l.mode()) && nz(l.ratePct()).signum() > 0 && l.startDate() != null) {
      BigDecimal principal = l.principalTaken() != null ? l.principalTaken() : nz(l.outstanding());
      int days = (int) ChronoUnit.DAYS.between(l.startDate(), l.dueDate());
      return principal.add(rupees(simpleInterest(principal, div(l.ratePct(), HUNDRED), days)));
    }
    return nz(l.outstanding());
  }

  // ── Simulation ─────────────────────────────────────────────────────────────

  private static final class State {
    final Loan loan;
    final boolean payInFull;
    final boolean dateBound;
    final YearMonth due;
    final BigDecimal monthlyRate;
    final BigDecimal contractual;
    BigDecimal balance;
    boolean carried;
    BigDecimal interest = BigDecimal.ZERO;
    BigDecimal firstSetAside;
    YearMonth clearedIn;
    /** An EMI loan with no EMI known: kept as it is and not simulated. */
    final boolean unknownSchedule;

    State(Loan l, LocalDate asOf, KaiConfig cfg) {
      loan = l;
      payInFull = payInFull(l);
      dateBound = !payInFull && dateBound(l);
      due = l.dueDate() == null ? null : YearMonth.from(l.dueDate());
      monthlyRate = div(div(nz(l.ratePct()), HUNDRED), TWELVE);
      contractual = nz(l.monthlyPayment());
      balance = dateBound ? dateBoundTarget(l, asOf, cfg) : nz(l.outstanding());
      unknownSchedule = !payInFull && !dateBound && contractual.signum() == 0
          && ("emi".equals(l.mode()) || "interest_only".equals(l.mode()))
          && nz(l.ratePct()).compareTo(cfg.prepay().splitFromPct()) < 0;
    }

    boolean open() { return balance.signum() > 0; }
  }

  private static final class Sim {
    final List<MonthRow> rows = new ArrayList<>();
    final List<State> states = new ArrayList<>();
    BigDecimal interest = BigDecimal.ZERO;
    boolean dateBoundFirst;

    List<LoanOutcome> outcomes() {
      return states.stream().map(s -> new LoanOutcome(s.loan.id(),
          s.unknownSchedule ? null : s.dateBound && s.clearedIn != null ? s.due : s.clearedIn,
          rupees(s.interest), s.firstSetAside)).toList();
    }

    YearMonth debtFreeBy() {
      YearMonth last = null;
      for (LoanOutcome o : outcomes()) {
        if (o.debtFreeBy() == null) {
          State s = states.stream().filter(x -> x.loan.id().equals(o.loanId())).findFirst().orElseThrow();
          if (s.unknownSchedule || s.open()) return null;
          continue;
        }
        if (last == null || o.debtFreeBy().isAfter(last)) last = o.debtFreeBy();
      }
      return last;
    }
  }

  private static Sim simulate(List<Loan> loans, BigDecimal surplus, LocalDate asOf, KaiConfig cfg,
                              String method, boolean baseline) {
    Sim sim = new Sim();
    for (Loan l : loans) {
      if (nz(l.outstanding()).signum() > 0) sim.states.add(new State(l, asOf, cfg));
    }
    YearMonth start = YearMonth.from(asOf).plusMonths(1);
    BigDecimal freed = BigDecimal.ZERO;
    YearMonth lastActive = start;
    for (int i = 0; i < HORIZON_MONTHS; i++) {
      YearMonth month = start.plusMonths(i);
      if (sim.states.stream().noneMatch(s -> s.open() && !s.unknownSchedule)) {
        if (!baseline) addRow(sim, month, Map.of(), surplus.add(freed), lastActive);
        if (i >= MIN_ROWS) break;
        continue;
      }
      lastActive = month;
      BigDecimal cash = surplus.add(freed);
      Map<String, BigDecimal> paid = new LinkedHashMap<>();

      // Interest and contractual payments (the contractual part is already in the budget).
      for (State s : sim.states) {
        if (!s.open() || s.dateBound || s.unknownSchedule) continue;
        if (s.payInFull) {
          if (s.carried) accrue(sim, s);
          continue;
        }
        accrue(sim, s);
        BigDecimal pay = s.contractual.min(s.balance);
        s.balance = s.balance.subtract(pay);
        if (!s.open()) clear(s, month);
      }

      List<State> fullStates = sim.states.stream().filter(s -> s.open() && s.payInFull)
          .sorted(Comparator.comparing((State s) -> s.monthlyRate).reversed()).toList();
      List<State> bound = sim.states.stream().filter(s -> s.open() && s.dateBound)
          .sorted(Comparator.comparing((State s) -> s.due)).toList();
      Map<State, BigDecimal> openingFull = new LinkedHashMap<>();
      fullStates.forEach(s -> openingFull.put(s, s.balance));

      if (baseline) {
        cash = payDateBound(bound, cash, month, paid, s -> {});
        cash = payFull(sim, fullStates, cash, BigDecimal.ZERO, paid, month);
      } else {
        BigDecimal required = requiredNow(bound, month, surplus.add(freed));
        BigDecimal before = cash;
        BigDecimal fullNeed = fullStates.stream().map(s -> s.balance).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (required.signum() > 0 && before.subtract(required).compareTo(fullNeed) < 0 && fullNeed.signum() > 0) {
          sim.dateBoundFirst = true;
        }
        cash = payFull(sim, fullStates, cash, required, paid, month);
        cash = payDateBound(bound, cash, month, paid, s -> {});
      }
      for (State s : bound) {
        if (s.firstSetAside == null && paid.containsKey(s.loan.id())) s.firstSetAside = paid.get(s.loan.id());
      }

      // A card balance not paid in full by its due date loses its interest-free period.
      for (var e : openingFull.entrySet()) {
        State s = e.getKey();
        if (!s.open()) continue;
        if (!s.carried) {
          BigDecimal interest = e.getValue().multiply(s.monthlyRate);
          s.balance = s.balance.add(interest);
          s.interest = s.interest.add(interest);
          sim.interest = sim.interest.add(interest);
        }
        s.carried = true;
      }

      if (!baseline) cash = prepay(sim, cash, cfg, method, paid, month);

      for (State s : sim.states) {
        if (s.clearedIn == month && !s.dateBound && !s.payInFull) freed = freed.add(s.contractual);
      }
      addRow(sim, month, paid, cash, lastActive);
    }
    return sim;
  }

  private static void addRow(Sim sim, YearMonth month, Map<String, BigDecimal> paid,
                             BigDecimal investable, YearMonth lastActive) {
    if (sim.rows.size() >= MAX_ROWS) return;
    if (sim.rows.size() >= MIN_ROWS && month.isAfter(lastActive.plusMonths(1))) return;
    sim.rows.add(new MonthRow(month, Map.copyOf(paid), rupees(investable)));
  }

  private static void accrue(Sim sim, State s) {
    BigDecimal interest = s.balance.multiply(s.monthlyRate);
    s.balance = s.balance.add(interest);
    s.interest = s.interest.add(interest);
    sim.interest = sim.interest.add(interest);
  }

  private static void clear(State s, YearMonth month) {
    s.balance = BigDecimal.ZERO;
    if (s.clearedIn == null) s.clearedIn = month;
  }

  /** The most this month must keep for date-bound amounts so every deadline stays reachable. */
  static BigDecimal requiredNow(List<State> bound, YearMonth month, BigDecimal futureCash) {
    BigDecimal required = BigDecimal.ZERO;
    BigDecimal cumulative = BigDecimal.ZERO;
    for (State s : bound) {
      cumulative = cumulative.add(s.balance);
      long left = Math.max(1, ChronoUnit.MONTHS.between(month, s.due) + 1);
      BigDecimal need = cumulative.subtract(futureCash.multiply(BigDecimal.valueOf(left - 1)));
      required = required.max(need);
    }
    return required;
  }

  private static BigDecimal payFull(Sim sim, List<State> states, BigDecimal cash, BigDecimal keep,
                                    Map<String, BigDecimal> paid, YearMonth month) {
    BigDecimal available = cash.subtract(keep).max(BigDecimal.ZERO);
    for (State s : states) {
      if (!s.open() || available.signum() <= 0) continue;
      BigDecimal pay = rupees(s.balance).min(available).min(s.balance.setScale(0, RoundingMode.CEILING));
      if (pay.signum() <= 0) continue;
      s.balance = s.balance.subtract(pay);
      if (s.balance.compareTo(BigDecimal.ONE) < 0) clear(s, month);
      available = available.subtract(pay);
      cash = cash.subtract(pay);
      paid.merge(s.loan.id(), pay, BigDecimal::add);
    }
    return cash;
  }

  private static BigDecimal payDateBound(List<State> bound, BigDecimal cash, YearMonth month,
                                         Map<String, BigDecimal> paid,
                                         java.util.function.Consumer<State> after) {
    for (State s : bound) {
      if (!s.open() || cash.signum() <= 0) continue;
      long left = ChronoUnit.MONTHS.between(month, s.due) + 1;
      BigDecimal share = left <= 1 ? s.balance : rupees(div(s.balance, BigDecimal.valueOf(left)));
      BigDecimal pay = share.min(s.balance).min(cash);
      if (pay.signum() <= 0) continue;
      s.balance = s.balance.subtract(pay);
      if (!s.open()) clear(s, month);
      cash = cash.subtract(pay);
      paid.merge(s.loan.id(), pay, BigDecimal::add);
      after.accept(s);
    }
    return cash;
  }

  /** Step 4: the prepay-or-invest rule over the remaining loans, then investing. */
  private static BigDecimal prepay(Sim sim, BigDecimal cash, KaiConfig cfg, String method,
                                   Map<String, BigDecimal> paid, YearMonth month) {
    Comparator<State> order = "snowball".equals(method)
        ? Comparator.comparing((State s) -> s.balance)
        : Comparator.comparing((State s) -> s.monthlyRate).reversed();
    List<State> rest = sim.states.stream()
        .filter(s -> s.open() && !s.payInFull && !s.dateBound && !s.unknownSchedule)
        .sorted(order).toList();
    for (State s : rest) {
      if (nz(s.loan.ratePct()).compareTo(cfg.prepay().repayAbovePct()) > 0) {
        cash = prepayOne(s, cash, paid, month);
      }
    }
    BigDecimal pool = rupees(cash.multiply(cfg.prepay().splitPrepayPct()).divide(HUNDRED, 10, RoundingMode.HALF_UP));
    for (State s : rest) {
      BigDecimal r = nz(s.loan.ratePct());
      if (r.compareTo(cfg.prepay().repayAbovePct()) <= 0 && r.compareTo(cfg.prepay().splitFromPct()) >= 0) {
        BigDecimal before = pool;
        pool = prepayOne(s, pool, paid, month);
        cash = cash.subtract(before.subtract(pool));
      }
    }
    return cash;
  }

  private static BigDecimal prepayOne(State s, BigDecimal cash, Map<String, BigDecimal> paid,
                                      YearMonth month) {
    if (!s.open() || cash.signum() <= 0) return cash;
    BigDecimal pay = s.balance.setScale(0, RoundingMode.CEILING).min(cash);
    s.balance = s.balance.subtract(pay);
    if (s.balance.signum() <= 0) clear(s, month);
    paid.merge(s.loan.id(), pay, BigDecimal::add);
    return cash.subtract(pay);
  }
}
