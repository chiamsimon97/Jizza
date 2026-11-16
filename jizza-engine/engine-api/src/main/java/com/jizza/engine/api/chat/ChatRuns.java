package com.jizza.engine.api.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.dto.RunDto;
import java.util.UUID;

/**
 * The start cards Jizza puts in a conversation when the person asks, in their own words, to run one
 * of their saved workflows. Pressing Start on a card is the person's go-ahead for that run, with
 * the values and browser on the card as they left them.
 */
public interface ChatRuns {

  /**
   * Starts the run a card in this conversation offers and records it on the card, so the card
   * shows the run when the conversation is opened again. A card starts one run only.
   *
   * @param planId    the card's id
   * @param inputs    the values as the person left them on the card
   * @param browserId the browser they chose; null for whichever of theirs is free
   * @throws com.jizza.engine.api.error.NotFoundException   when the card is not in their conversation
   * @throws com.jizza.engine.api.error.ConflictException   when the card already started a run
   * @throws com.jizza.engine.api.error.ValidationException when the workflow refuses the values
   */
  RunDto start(UUID userId, UUID conversationId, UUID planId, UUID workflowId, JsonNode inputs,
               UUID browserId);

  /** The person said no to the card; it stays in the conversation, closed. */
  void dismiss(UUID userId, UUID conversationId, UUID planId);
}
