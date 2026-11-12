package com.jizza.engine.api.chat;

import com.jizza.engine.api.orchestrator.Orchestrator;
import java.util.Optional;
import java.util.UUID;

/**
 * A chat message the app answers itself, without a model call: a slash command such as
 * {@code /run <workflow>}. It is stored and shown like any other turn, and works even when the
 * model is out of budget.
 */
public interface ChatShortcut {

  /**
   * The reply to {@code text}, or empty when this shortcut does not handle it and the turn goes to
   * Jizza as usual.
   *
   * @param conversationId the conversation the message is in; null for an incognito turn
   */
  Optional<Orchestrator.Turn> handle(UUID userId, UUID conversationId, String text);
}
