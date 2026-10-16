package com.jizza.engine.autonomy.guardrail;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.persistence.entity.AutonomyPolicyEntity;
import com.jizza.engine.persistence.entity.GuardrailUsageEntity;
import com.jizza.engine.persistence.repository.AutonomyPolicyRepository;
import com.jizza.engine.persistence.repository.GuardrailUsageRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Running totals for guardrails with a budget or a call limit.
 *
 * <p>A per-call cap is easy to get around by splitting one large order into several small ones; a
 * budget over a period is not. A reservation locks the rule's row while it adds up what was spent,
 * so two calls racing for the last of a budget cannot both get it. A reserved call that then fails
 * still counts, which errs on the side of asking sooner.
 */
@Service
public class GuardrailLedger {

  private final AutonomyPolicyRepository policies;
  private final GuardrailUsageRepository usage;

  public GuardrailLedger(AutonomyPolicyRepository policies, GuardrailUsageRepository usage) {
    this.policies = policies;
    this.usage = usage;
  }

  /** Whether the call would go over, without spending anything. For explanations and previews. */
  @Transactional(readOnly = true)
  public Optional<String> wouldExceed(AutonomyPolicyEntity policy, JsonNode args, Instant now) {
    return exceeded(policy, args, now);
  }

  /**
   * Counts the call against the rule when it fits.
   *
   * @return why it does not fit, or empty when it was counted
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public Optional<String> reserve(UUID policyId, UUID userId, String toolRef, JsonNode args) {
    AutonomyPolicyEntity policy = policies.lockById(policyId).orElse(null);
    if (policy == null) return Optional.of("The rule that allowed this no longer exists");
    Instant now = Instant.now();
    Optional<String> over = exceeded(policy, args, now);
    if (over.isPresent()) return over;

    GuardrailUsageEntity row = new GuardrailUsageEntity();
    row.setPolicyId(policyId);
    row.setUserId(userId);
    row.setToolRef(toolRef);
    row.setCreatedAt(now);
    JsonNode budget = policy.getLimits() == null ? null : policy.getLimits().get("budget");
    if (budget != null && !budget.isNull()) {
      BigDecimal amount = GuardrailConditions.number(args, budget.path("field").asText("amount"));
      row.setAmount(amount == null ? null : amount.abs());
    }
    usage.save(row);
    return Optional.empty();
  }

  private Optional<String> exceeded(AutonomyPolicyEntity policy, JsonNode args, Instant now) {
    JsonNode limits = policy.getLimits();
    if (limits == null || !limits.isObject()) return Optional.empty();

    JsonNode calls = limits.get("maxCalls");
    if (calls != null && !calls.isNull()) {
      long max = calls.path("count").asLong(0);
      String per = calls.path("per").asText("P1D");
      Instant since = now.minus(GuardrailConditions.period(per));
      long used = usage.countByPolicyIdAndCreatedAtAfter(policy.getId(), since);
      if (used + 1 > max) {
        return Optional.of("Your rule allows " + max + " of these " + describe(per)
            + " and that many have already run, so this one needs your approval");
      }
    }

    JsonNode budget = limits.get("budget");
    if (budget != null && !budget.isNull()) {
      String field = budget.path("field").asText("amount");
      BigDecimal amount = GuardrailConditions.number(args, field);
      if (amount == null) {
        // A budget can only be kept if every call says how much it spends.
        return Optional.of("This doesn't say its " + field + ", so it can't be counted against "
            + "your budget and needs your approval");
      }
      BigDecimal max = budget.path("max").decimalValue();
      String per = budget.path("per").asText("P1D");
      Instant since = now.minus(GuardrailConditions.period(per));
      BigDecimal spent = usage.spentSince(policy.getId(), since);
      if (spent.add(amount.abs()).compareTo(max) > 0) {
        return Optional.of("This would take " + field + " to " + plain(spent.add(amount.abs()))
            + " " + describe(per) + ", over your budget of " + plain(max)
            + ", so it needs your approval");
      }
    }
    return Optional.empty();
  }

  private static String plain(BigDecimal value) {
    return value.stripTrailingZeros().toPlainString();
  }

  static String describe(String period) {
    return switch (period == null ? "P1D" : period) {
      case "P1D", "PT24H" -> "a day";
      case "P7D" -> "a week";
      case "P30D", "P31D" -> "a month";
      default -> "per " + period;
    };
  }
}
