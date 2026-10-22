package com.jizza.engine.api.orchestrator;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.llm.Msg;
import java.util.List;
import java.util.UUID;

/**
 * Jizza: the only agent a user message reaches (R18).
 *
 * <p>This is the reasoning only. It stores nothing; the chat module owns conversations, history
 * and jobs, and hands Jizza the history it should see. When a job is current on the thread
 * ({@code Activity}), Jizza's reply streams to it as it is written.
 */
public interface Orchestrator {

  Turn respond(UUID userId, TurnInput input);

  /**
   * @param screen        the page the user is on when the message comes from inside the app; it is
   *                      context for this turn only and is never stored
   * @param userMessageId the stored message this turn answers, null for an incognito turn; memory
   *                      reads are stamped with it
   * @param context       what is open on that screen, such as the Builder step Jizza is configuring;
   *                      like the screen, context for this turn only
   */
  record TurnInput(String message, String screen, List<Msg> history, UUID userMessageId,
                   JsonNode context) {
    public TurnInput {
      history = history == null ? List.of() : List.copyOf(history);
    }

    public TurnInput(String message, String screen, List<Msg> history, UUID userMessageId) {
      this(message, screen, history, userMessageId, null);
    }
  }

  /** Jizza's reply and what went into it. */
  record Turn(String text, List<Delegation> delegations, List<UiAction> actions,
              int inputTokens, int outputTokens) {
    public Turn {
      delegations = delegations == null ? List.of() : List.copyOf(delegations);
      actions = actions == null ? List.of() : List.copyOf(actions);
    }
  }

  /** A specialist Jizza consulted, the task it was given and what it answered. */
  record Delegation(UUID fromAgentId, UUID agentId, String agentName, String task, String result) {}
}
