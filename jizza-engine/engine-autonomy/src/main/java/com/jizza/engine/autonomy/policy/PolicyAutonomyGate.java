package com.jizza.engine.autonomy.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.autonomy.AutonomyGate;
import com.jizza.engine.api.autonomy.Decision;
import com.jizza.engine.api.enums.AutonomyRule;
import com.jizza.engine.persistence.entity.AgentEntity;
import com.jizza.engine.persistence.entity.AutonomyPolicyEntity;
import com.jizza.engine.persistence.repository.AgentRepository;
import com.jizza.engine.persistence.repository.AutonomyPolicyRepository;
import com.jizza.engine.persistence.repository.GeneratedToolRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The per-agent, per-tool autonomy policy (R41), consulted before every tool call and every
 * autonomous run.
 *
 * <p>A rule matches on agent and tool. {@code tool_ref} may be an exact tool name, a prefix glob
 * ({@code gmail_*}), one of the two category tokens {@link #ANY_READ} and
 * {@link #ANY_SIDE_EFFECTING}, or {@code *} for everything. The most specific matching rule wins,
 * with {@code precedence} breaking ties in the user's favour.
 *
 * <p>When nothing matches at all the answer is the Charter's conservative default, in code rather
 * than in data: reading and informing are automatic, anything that sends, pays, creates, deletes or
 * otherwise changes the outside world is {@code ask}. Deleting every policy row therefore makes the
 * system safer, not permissive.
 */
@Service
public class PolicyAutonomyGate implements AutonomyGate {

  public static final String ANY = "*";
  public static final String ANY_READ = "*:read";
  public static final String ANY_SIDE_EFFECTING = "*:side-effecting";

  /** Built-in tools that only read or only speak to the user, and so are safe unattended. */
  private static final List<String> READ_ONLY_BUILTINS = List.of(
      "knowledge_search", "knowledge_get", "knowledge_upsert",
      "delegate_to_specialist", "delegate_to_specialists",
      "notify_user", "request_permission", "propose_workflow");

  private final AutonomyPolicyRepository policies;
  private final AgentRepository agents;
  private final GeneratedToolRepository tools;

  public PolicyAutonomyGate(AutonomyPolicyRepository policies, AgentRepository agents,
                            GeneratedToolRepository tools) {
    this.policies = policies;
    this.agents = agents;
    this.tools = tools;
  }

  @Override
  @Transactional(readOnly = true)
  public Decision evaluate(UUID agentId, String toolRef, JsonNode args) {
    Optional<AgentEntity> agent = agents.findById(agentId);
    if (agent.isEmpty()) return Decision.deny("Unknown agent", "no-agent");
    if (!agent.get().isEnabled()) {
      return Decision.deny("Agent " + agent.get().getName() + " is disabled", "agent-disabled");
    }
    if (toolRef == null) return Decision.allow("no-tool");

    boolean sideEffecting = isSideEffecting(agent.get().getUserId(), toolRef);
    Optional<AutonomyPolicyEntity> matched =
        policies.findByUserIdOrderByPrecedenceDesc(agent.get().getUserId()).stream()
            .filter(policy -> matches(policy, agentId, toolRef, sideEffecting))
            .max(Comparator.comparingInt(AutonomyPolicyEntity::getPrecedence)
                .thenComparingInt(policy -> policy.getAgentId() == null ? 0 : 1)
                .thenComparingInt(policy -> specificity(policy.getToolRef())));

    if (matched.isEmpty()) {
      return sideEffecting
          ? Decision.ask("This changes something outside Jizza, so it needs your approval",
              "default-conservative")
          : Decision.allow("default-conservative");
    }

    AutonomyPolicyEntity policy = matched.get();
    String label = describe(policy);
    if (policy.getRule() == AutonomyRule.DENY) {
      return Decision.deny("The autonomy policy forbids " + toolRef, label);
    }
    if (policy.getRule() == AutonomyRule.ASK) {
      return Decision.ask("The autonomy policy requires your approval for " + toolRef, label);
    }
    // An allow rule can still be capped: an over-limit call is routed to approval rather than
    // refused outright, so the user can wave it through when it is genuinely intended.
    return overLimit(policy, args)
        .<Decision>map(reason -> Decision.ask(reason, label))
        .orElseGet(() -> Decision.allow(label));
  }

  @Override
  @Transactional(readOnly = true)
  public boolean agentEnabled(UUID agentId) {
    return agents.findById(agentId).map(AgentEntity::isEnabled).orElse(false);
  }

  boolean isSideEffecting(UUID userId, String toolRef) {
    if (READ_ONLY_BUILTINS.contains(toolRef)) return false;
    // A skill runs a whole workflow, which may do anything; treat it as consequential.
    if (toolRef.startsWith("skill_")) return true;
    if ("schedule_task".equals(toolRef)) return true;

    return tools.findEnabledForUser(userId).stream()
        .filter(tool -> tool.getName().equals(toolRef))
        .findFirst()
        .map(com.jizza.engine.persistence.entity.GeneratedToolEntity::isSideEffecting)
        // An unknown tool is treated as consequential: unrecognised is not the same as harmless.
        .orElse(true);
  }

  private boolean matches(AutonomyPolicyEntity policy, UUID agentId, String toolRef,
                          boolean sideEffecting) {
    if (policy.getAgentId() != null && !policy.getAgentId().equals(agentId)) return false;

    String ref = policy.getToolRef();
    if (ref == null || ref.isBlank() || ANY.equals(ref)) return true;
    if (ANY_READ.equals(ref)) return !sideEffecting;
    if (ANY_SIDE_EFFECTING.equals(ref)) return sideEffecting;
    if (ref.endsWith("*")) {
      return toolRef.toLowerCase(Locale.ROOT)
          .startsWith(ref.substring(0, ref.length() - 1).toLowerCase(Locale.ROOT));
    }
    return ref.equalsIgnoreCase(toolRef);
  }

  private int specificity(String ref) {
    if (ref == null || ref.isBlank() || ANY.equals(ref)) return 10;
    if (ANY_READ.equals(ref) || ANY_SIDE_EFFECTING.equals(ref)) return 30;
    if (ref.endsWith("*")) return 60;
    return 100;
  }

  /**
   * Applies a value cap such as {@code {"field":"amount","maxValue":5000}}. The field may be a
   * dotted path into the arguments.
   */
  private Optional<String> overLimit(AutonomyPolicyEntity policy, JsonNode args) {
    JsonNode limits = policy.getLimits();
    if (limits == null || limits.isNull() || args == null || args.isNull()) return Optional.empty();
    if (!limits.hasNonNull("maxValue")) return Optional.empty();

    String field = limits.path("field").asText("amount");
    JsonNode value = args;
    for (String segment : field.split("\\.")) {
      value = value.path(segment);
    }
    if (!value.isNumber()) return Optional.empty();

    double max = limits.get("maxValue").asDouble();
    if (value.asDouble() > max) {
      return Optional.of("This exceeds the configured limit of " + max + " for " + field
          + ", so it needs your approval");
    }
    return Optional.empty();
  }

  private String describe(AutonomyPolicyEntity policy) {
    return (policy.getAgentId() == null ? "global" : "agent") + ":"
        + (policy.getToolRef() == null ? ANY : policy.getToolRef());
  }
}
