package com.jizza.platform.usecase.vita.ranges;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The target evaluator (spec 5.1): a pure function from a reading, the range that applies and the
 * red-flag rules to a status. No figure lives here; every bound arrives as input. A red flag
 * outranks the range; a reading with no range is "not set", unless it is a red flag.
 */
public final class TargetEvaluator {

  public static final String IN_RANGE = "in_range";
  public static final String OUTSIDE_RANGE = "outside_range";
  public static final String RED_FLAG = "red_flag";
  public static final String NOT_SET = "not_set";
  public static final String NOT_SET_LABEL = "Not set: ask your doctor";

  private TargetEvaluator() {}

  /** One reading: for blood pressure {@code value} is the systolic and {@code value2} the diastolic. */
  public record Reading(String kind, BigDecimal value, BigDecimal value2, String context, String setting) {}

  /** A red flag that fired, at its level after the answer to its question. */
  public record RedFlagHit(String ruleId, String level, String advice, String question, List<String> signs,
                           boolean veryLow, boolean needsVerification) {}

  /**
   * The result. {@code outside} names each bound crossed ("systolic high", "low"); {@code range}
   * is the range used in words; {@code label} says who set it.
   */
  public record Evaluation(String status, List<String> outside, String range, String rangeSource,
                           UUID doctorId, String doctorName, LocalDate setOn, boolean needsVerification,
                           String label, RedFlagHit redFlag) {

    public boolean redFlagged() { return redFlag != null; }
  }

  /**
   * @param signsNow the answer to a rule's question ("Any of these now?"): true raises urgent to
   *                 emergency; null when not asked yet
   */
  public static Evaluation evaluate(Reading r, RangeSet range, List<RedFlagRule> rules, Boolean signsNow) {
    RedFlagHit hit = null;
    for (RedFlagRule rule : rules == null ? List.<RedFlagRule>of() : rules) {
      if (rule.matches(r.kind(), r.value(), r.value2())) {
        String level = Boolean.TRUE.equals(signsNow) ? rule.raisedTo() : rule.level();
        String advice = RedFlagRule.EMERGENCY.equals(level) && rule.emergencyAdvice() != null
            ? rule.emergencyAdvice() : rule.advice();
        hit = new RedFlagHit(rule.id(), level, advice, rule.question(), rule.signs(),
            rule.veryLow(r.value()), rule.needsVerification());
        break;
      }
    }
    List<String> outside = new ArrayList<>();
    if (range != null) {
      boolean bp = "bp".equals(r.kind());
      if (range.value() != null && r.value() != null) {
        String side = range.value().side(r.value());
        if (side != null) outside.add(bp ? "systolic " + side : side);
      }
      if (bp && range.value2() != null && r.value2() != null) {
        String side = range.value2().side(r.value2());
        if (side != null) outside.add("diastolic " + side);
      }
    }
    String status = hit != null ? RED_FLAG
        : range == null ? NOT_SET
        : outside.isEmpty() ? IN_RANGE : OUTSIDE_RANGE;
    if (range == null) {
      return new Evaluation(status, List.of(), null, null, null, null, null, false, NOT_SET_LABEL, hit);
    }
    return new Evaluation(status, List.copyOf(outside), range.text(), range.source(), range.doctorId(),
        range.doctorName(), range.setOn(), range.needsVerification(), range.label(), hit);
  }
}
