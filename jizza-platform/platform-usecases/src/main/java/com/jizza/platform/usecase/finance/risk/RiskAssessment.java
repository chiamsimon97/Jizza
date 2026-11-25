package com.jizza.platform.usecase.finance.risk;

import com.jizza.engine.api.error.ValidationException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Risk profile, questionnaire version 1 (spec 2A.4). Tolerance comes from six loss-scenario
 * answers (1 to 3 each: 6–9 Conservative, 10–14 Moderate, 15–18 Aggressive). Capacity comes from
 * five facts in the user's own data (1 to 3 each: 5–8 Conservative, 9–12 Moderate, 13–15
 * Aggressive). The profile is the lower of the two; a conflict is shown, never averaged.
 *
 * <p>A fact Kai has no data for counts as the middle answer (2) and is listed as assumed. The
 * result is indicative: it is not the suitability assessment a SEBI-registered adviser performs.
 */
public final class RiskAssessment {

  private RiskAssessment() {}

  public static final int VERSION = 1;
  public static final List<String> ORDER = List.of("conservative", "moderate", "aggressive");

  /** The six tolerance questions with their three answers, lowest score first. */
  public static final List<Question> QUESTIONS = List.of(
      new Question("loss", "Your ₹10 lakh falls to ₹7 lakh in a year. What do you do?",
          List.of("Sell everything", "Wait", "Invest more")),
      new Question("need", "When will you need this money?",
          List.of("In under 3 years", "In 3 to 7 years", "In 7 years or more")),
      new Question("range", "Which yearly result would you choose?",
          List.of("6% to 8%, steady", "0% to 14%", "−15% to +30%")),
      new Question("lastFall", "In the last market fall, you:",
          List.of("Sold", "Did nothing", "Bought more")),
      new Question("patience", "How long could you leave a falling investment alone?",
          List.of("Under 6 months", "Up to 2 years", "Longer")),
      new Question("late", "Missing a goal by a year would be:",
          List.of("A serious problem", "Uncomfortable", "Manageable")));

  public record Question(String key, String text, List<String> answers) {}

  /**
   * The capacity facts as Kai read them; null where there is no data.
   *
   * @param stableEarners earners whose income is stable (0 with any income means variable)
   * @param hasIncome whether any income is recorded
   * @param dependants people who depend on the household
   * @param emergencyMonths emergency money in months of essential spending
   * @param debtToIncomePct monthly debt payments as % of gross income
   * @param yearsToNearestImportantGoal years to the nearest Important goal
   */
  public record Facts(Integer stableEarners, boolean hasIncome, Integer dependants,
                      BigDecimal emergencyMonths, BigDecimal debtToIncomePct,
                      BigDecimal yearsToNearestImportantGoal) {}

  public record FactScore(String key, String label, int score, boolean assumed, String reading) {}

  public record Result(int toleranceScore, String tolerance, int capacityScore, String capacity,
                       String profile, boolean conflict, String decidedBy, List<FactScore> facts,
                       String explanation) {}

  public static Result assess(List<Integer> answers, Facts facts) {
    if (answers == null || answers.size() != QUESTIONS.size()) {
      throw new ValidationException("Answer all " + QUESTIONS.size() + " questions (1, 2 or 3 each).");
    }
    int tol = 0;
    for (Integer a : answers) {
      if (a == null || a < 1 || a > 3) throw new ValidationException("Each answer must be 1, 2 or 3.");
      tol += a;
    }
    List<FactScore> scored = capacityFacts(facts);
    int cap = scored.stream().mapToInt(FactScore::score).sum();
    String tolerance = tol <= 9 ? "conservative" : tol <= 14 ? "moderate" : "aggressive";
    String capacity = cap <= 8 ? "conservative" : cap <= 12 ? "moderate" : "aggressive";
    String profile = ORDER.indexOf(tolerance) <= ORDER.indexOf(capacity) ? tolerance : capacity;
    boolean conflict = !tolerance.equals(capacity);
    String decidedBy = !conflict ? "both" : profile.equals(capacity) ? "capacity" : "tolerance";
    String explanation = !conflict
        ? "Your answers and your finances both point to " + word(profile) + "."
        : "capacity".equals(decidedBy)
            ? "Your answers say " + word(tolerance) + ", but your finances can carry " + word(capacity)
                + " risk. Kai uses the lower: " + word(profile) + "."
            : "Your finances could carry " + word(capacity) + " risk, but your answers say "
                + word(tolerance) + ". Kai uses the lower: " + word(profile) + ".";
    return new Result(tol, tolerance, cap, capacity, profile, conflict, decidedBy, scored, explanation);
  }

  static List<FactScore> capacityFacts(Facts f) {
    List<FactScore> out = new ArrayList<>();
    if (f == null || !f.hasIncome() || f.stableEarners() == null) {
      out.add(new FactScore("incomeStability", "Income stability", 2, true, "No income recorded"));
    } else {
      int s = f.stableEarners() >= 2 ? 3 : f.stableEarners() == 1 ? 2 : 1;
      out.add(new FactScore("incomeStability", "Income stability", s, false,
          s == 3 ? "Two or more stable earners" : s == 2 ? "One stable earner" : "Variable income"));
    }
    if (f == null || f.dependants() == null) {
      out.add(new FactScore("dependants", "Dependants", 2, true, "Not known"));
    } else {
      int d = f.dependants();
      out.add(new FactScore("dependants", "Dependants", d >= 3 ? 1 : d >= 1 ? 2 : 3, false,
          d == 0 ? "None" : d + (d == 1 ? " person" : " people")));
    }
    if (f == null || f.emergencyMonths() == null) {
      out.add(new FactScore("emergencyFund", "Emergency fund", 2, true, "Not known"));
    } else {
      BigDecimal m = f.emergencyMonths();
      int s = m.compareTo(BigDecimal.valueOf(6)) >= 0 ? 3 : m.compareTo(BigDecimal.valueOf(3)) >= 0 ? 2 : 1;
      out.add(new FactScore("emergencyFund", "Emergency fund", s, false, plain(m) + " months"));
    }
    if (f == null || f.debtToIncomePct() == null) {
      out.add(new FactScore("debtToIncome", "Loan payments to income", 2, true, "Not known"));
    } else {
      BigDecimal p = f.debtToIncomePct();
      int s = p.compareTo(BigDecimal.valueOf(40)) > 0 ? 1 : p.compareTo(BigDecimal.valueOf(30)) >= 0 ? 2 : 3;
      out.add(new FactScore("debtToIncome", "Loan payments to income", s, false, plain(p) + "%"));
    }
    if (f == null || f.yearsToNearestImportantGoal() == null) {
      out.add(new FactScore("nearestGoal", "Years to the nearest important goal", 2, true, "No important goal yet"));
    } else {
      BigDecimal y = f.yearsToNearestImportantGoal();
      int s = y.compareTo(BigDecimal.valueOf(3)) < 0 ? 1 : y.compareTo(BigDecimal.valueOf(7)) <= 0 ? 2 : 3;
      out.add(new FactScore("nearestGoal", "Years to the nearest important goal", s, false, plain(y) + " years"));
    }
    return out;
  }

  public static String word(String profile) {
    return profile.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + profile.substring(1);
  }

  private static String plain(BigDecimal v) {
    return v.setScale(1, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
  }
}
