package com.jizza.platform.usecase.finance.debt;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a rate as the user knows it into an effective rate in whole percent a year (5.13):
 * "14%" or "14% a year" is 14; "₹2 per ₹100 per month" or "2% a month" is 24; a flat rate on a
 * consumer EMI becomes its reducing-balance equivalent once the tenure is known.
 */
public final class RateNormaliser {

  private RateNormaliser() {}

  private static final Pattern NUMBER = Pattern.compile("(\\d+(?:\\.\\d+)?)");
  private static final Pattern PER_HUNDRED = Pattern.compile(
      "(\\d+(?:\\.\\d+)?)\\s*(?:rs\\.?|rupees?)?\\s*(?:per|/|on|for)\\s*(?:rs\\.?)?\\s*100\\b");
  private static final Pattern MONTHLY = Pattern.compile(
      "(month|monthly|/\\s*m\\b|\\bp\\.?\\s*m\\.?\\b|\\bpm\\b|mahina|mahine)");

  /** The effective annual rate, or null when the text holds no number. */
  public static BigDecimal effectiveAnnualPct(String input, String rateType, Integer tenureMonths) {
    if (input == null || input.isBlank()) return null;
    String s = input.toLowerCase(Locale.ROOT).replace("₹", "").replace(",", "").trim();
    if (s.equals("zero") || s.equals("nil") || s.equals("none") || s.contains("no interest")) {
      return BigDecimal.ZERO;
    }
    boolean monthly = MONTHLY.matcher(s).find();
    Matcher perHundred = PER_HUNDRED.matcher(s);
    BigDecimal rate;
    if (perHundred.find()) {
      rate = new BigDecimal(perHundred.group(1));
      monthly = monthly || !s.contains("year");
    } else {
      Matcher m = NUMBER.matcher(s);
      if (!m.find()) return null;
      rate = new BigDecimal(m.group(1));
    }
    BigDecimal annual = monthly ? rate.multiply(BigDecimal.valueOf(12)) : rate;
    boolean flat = "flat".equals(rateType) || s.contains("flat");
    if (flat && tenureMonths != null && tenureMonths > 0) annual = flatToReducing(annual, tenureMonths);
    return annual.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros();
  }

  /**
   * The reducing-balance rate a year that gives the same EMI as a flat rate over {@code months}:
   * EMI = P × (1 + flat × n / 12) / n, solved for the monthly rate by bisection.
   */
  public static BigDecimal flatToReducing(BigDecimal flatAnnualPct, int months) {
    double flat = flatAnnualPct.doubleValue() / 100.0;
    double emiPerRupee = (1 + flat * months / 12.0) / months;
    if (flat <= 0) return BigDecimal.ZERO;
    double lo = 0, hi = 1;
    for (int i = 0; i < 200; i++) {
      double mid = (lo + hi) / 2;
      double emi = mid / (1 - Math.pow(1 + mid, -months));
      if (emi > emiPerRupee) hi = mid; else lo = mid;
    }
    return BigDecimal.valueOf(lo * 12 * 100).setScale(2, RoundingMode.HALF_UP);
  }
}
