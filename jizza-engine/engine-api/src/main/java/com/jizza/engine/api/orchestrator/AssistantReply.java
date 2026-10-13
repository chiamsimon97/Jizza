package com.jizza.engine.api.orchestrator;

import java.util.List;
import java.util.UUID;

/**
 * Jizza's single-voice answer. {@code contributors} names the specialists that fed it so the UI can
 * attribute them without ever letting a specialist speak to the user directly (R19/R20).
 * {@code actions} are the steps Jizza wants the user's screen to take alongside the text.
 */
public record AssistantReply(UUID conversationId, UUID messageId, String text,
                             List<Contribution> contributors, List<UiAction> actions) {

  public AssistantReply {
    contributors = contributors == null ? List.of() : List.copyOf(contributors);
    actions = actions == null ? List.of() : List.copyOf(actions);
  }

  public AssistantReply(UUID conversationId, UUID messageId, String text,
                        List<Contribution> contributors) {
    this(conversationId, messageId, text, contributors, List.of());
  }

  public record Contribution(UUID agentId, String agentName, String summary) {}
}
