package com.jizza.engine.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.enums.AutonomyRule;
import java.util.UUID;

/**
 * One guardrail.
 *
 * @param agentId         null means the rule is global for the user
 * @param limits          conditions on a looser rule: {@code condition} (an expression over
 *                        {@code args}), {@code maxValue}/{@code field}, {@code budget},
 *                        {@code maxCalls} and {@code activeHours}. When one fails the call falls
 *                        through to the next rule, and never to anything looser than ask.
 * @param workflowId      when set, the rule only applies inside runs of this workflow
 * @param workflowVersion when set too, only inside runs of that approved version
 * @param nodeId          when set too, only to that one step
 * @param locked          a ceiling: nothing more specific may be looser than it
 * @param workspaceId     an organization's ceiling, applying to every member's agents when they
 *                        use that organization's connectors; always locked
 */
public record AutonomyRuleDto(UUID id, UUID userId, UUID agentId, String toolRef,
                              AutonomyRule rule, JsonNode limits, int precedence,
                              UUID workflowId, Integer workflowVersion, String nodeId,
                              boolean locked, UUID workspaceId) {

  public AutonomyRuleDto(UUID id, UUID userId, UUID agentId, String toolRef, AutonomyRule rule,
                         JsonNode limits, int precedence) {
    this(id, userId, agentId, toolRef, rule, limits, precedence, null, null, null, false, null);
  }

  public AutonomyRuleDto(UUID id, UUID userId, UUID agentId, String toolRef, AutonomyRule rule,
                         JsonNode limits, int precedence, UUID workflowId, Integer workflowVersion,
                         String nodeId, boolean locked) {
    this(id, userId, agentId, toolRef, rule, limits, precedence, workflowId, workflowVersion,
        nodeId, locked, null);
  }
}
