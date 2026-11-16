package com.jizza.engine.api.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.orchestrator.Orchestrator;
import java.util.Optional;
import java.util.UUID;

/**
 * A chat message the app answers itself, without Jizza's full turn: a slash command such as
 * {@code /run <workflow>}, or a plain request to run one of the user's saved workflows. It is stored and shown like any other turn, and works even when the
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

  /**
   * As above, with what the client said about the turn ({@code timeZone}, the screen it was sent
   * from); null when it said nothing.
   */
  default Optional<Orchestrator.Turn> handle(UUID userId, UUID conversationId, String text,
                                             JsonNode context) {
    return handle(userId, conversationId, text);
  }

  /**
   * Whether this shortcut only answers messages that start with {@code /}. One that also answers
   * plain words (running a saved workflow the user asked for in their own words) is asked about
   * every message the user types in a conversation, after the slash commands, and must stay cheap
   * when the message is not for it.
   */
  default boolean slashOnly() {
    return true;
  }
}
