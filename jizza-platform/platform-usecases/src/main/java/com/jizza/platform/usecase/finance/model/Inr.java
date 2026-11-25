package com.jizza.platform.usecase.finance.model;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Rupee arithmetic and wording: whole rupees rounded half up, Indian grouping, lakh and crore. */
public final class Inr {

  private Inr() {}

  public static final BigDecimal ZERO = BigDecimal.ZERO;
  public static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
  public static final BigDecimal TWELVE = BigDecimal.valueOf(12);
  private static final BigDecimal LAKH = BigDecimal.valueOf(100_000);
  private static final BigDecimal CRORE = BigDecimal.valueOf(10_000_000);

  public static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }

  /** Whole rupees, half up. */
  public static BigDecimal rupees(BigDecimal v) { return nz(v).setScale(0, RoundingMode.HALF_UP); }

  /** One decimal, half up (percentages and months as shown). */
  public static BigDecimal one(BigDecimal v) { return v.setScale(1, RoundingMode.HALF_UP); }

  public static BigDecimal div(BigDecimal a, BigDecimal b) {
    return a.divide(b, 10, RoundingMode.HALF_UP);
  }

  public static BigDecimal pct(BigDecimal part, BigDecimal whole) {
    return div(part.multiply(HUNDRED), whole);
  }

  /** "₹22,000", "₹1.5 lakh", "₹2.72 crore". */
  public static String words(BigDecimal amount) {
    BigDecimal v = nz(amount).abs();
    String sign = nz(amount).signum() < 0 ? "-" : "";
    if (v.compareTo(CRORE) >= 0) return sign + "₹" + short2(div(v, CRORE)) + " crore";
    if (v.compareTo(LAKH) >= 0) return sign + "₹" + short2(div(v, LAKH)) + " lakh";
    return sign + group(rupees(v));
  }

  /** "₹1,20,000": Indian digit grouping. */
  public static String group(BigDecimal amount) {
    String digits = rupees(amount).abs().toPlainString();
    String sign = amount.signum() < 0 ? "-" : "";
    if (digits.length() <= 3) return sign + "₹" + digits;
    String last3 = digits.substring(digits.length() - 3);
    String rest = digits.substring(0, digits.length() - 3);
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < rest.length(); i++) {
      if (i > 0 && (rest.length() - i) % 2 == 0) sb.append(',');
      sb.append(rest.charAt(i));
    }
    return sign + "₹" + sb + "," + last3;
  }

  /** A percentage for a sentence: "14%", "16.7%". */
  public static String pctWords(BigDecimal pct) {
    return short2(one(pct)) + "%";
  }

  private static String short2(BigDecimal v) {
    BigDecimal r = v.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros();
    return r.scale() < 0 ? r.setScale(0).toPlainString() : r.toPlainString();
  }
}
