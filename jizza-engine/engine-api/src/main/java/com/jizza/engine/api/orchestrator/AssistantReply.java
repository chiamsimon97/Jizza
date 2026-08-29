package com.jizza.engine.api.orchestrator;

import java.util.List;
import java.util.UUID;

/**
 * Jizza's single-voice answer. {@code contributors} names the specialists that fed it so the UI can
 * attribute them without ever letting a specialist speak to the user directly (R19/R20).
 */
public record AssistantReply(UUID conversationId, UUID messageId, String text,
                             List<Contribution> contributors) {

  public record Contribution(UUID agentId, String agentName, String summary) {}
}
