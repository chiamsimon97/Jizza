package com.jizza.engine.api.orchestrator;

import com.jizza.engine.api.dto.ConversationDto;
import com.jizza.engine.api.dto.ConversationSummaryDto;
import com.jizza.engine.api.dto.EarlierSummaryDto;
import com.jizza.engine.api.dto.LearnedFactDto;
import com.jizza.engine.api.dto.ThreadGuideDto;
import java.util.List;
import java.util.UUID;

/** The user's conversations. Every method checks the conversation belongs to {@code userId}. */
public interface ConversationStore {

  List<ConversationDto> list(UUID userId, int limit);

  /** Newest activity first, pinned on top. {@code query} matches titles and message text. */
  List<ConversationSummaryDto> summaries(UUID userId, int limit, boolean archived, String query);

  ConversationDto get(UUID userId, UUID conversationId);

  List<ConversationDto.MessageDto> messages(UUID userId, UUID conversationId);

  /** {@code threads} turns threads on for the new chat. */
  ConversationSummaryDto create(UUID userId, String title, boolean threads);

  /** One conversation's list row: its title, pin and counts. */
  ConversationSummaryDto summary(UUID userId, UUID conversationId);

  /**
   * Any argument left null is unchanged. A title set here is the user's and is never replaced
   * automatically. {@code threads} applies to a main chat only; its existing threads stay either way.
   */
  ConversationSummaryDto update(UUID userId, UUID conversationId, String title, Boolean pinned,
                                Boolean archived, Boolean threads);

  /** A main chat's threads, most recently active first, archived ones included. */
  List<ConversationSummaryDto> threads(UUID userId, UUID conversationId);

  /**
   * The long-term memories learned from what the user said in this conversation, newest first. For
   * a thread, its own memory: facts learned in one thread are not recalled in another.
   */
  List<LearnedFactDto> learned(UUID userId, UUID conversationId);

  /**
   * Takes the conversation back to just before the user's message {@code messageId}: it and
   * everything after it are removed, so the edited message can be sent in its place.
   */
  void rewind(UUID userId, UUID conversationId, UUID messageId);

  /**
   * The summary that stands in for the conversation's older messages, or null while every message
   * is still sent to the model as written.
   */
  default EarlierSummaryDto earlier(UUID userId, UUID conversationId) {
    return null;
  }

  /**
   * The user's thumbs up or down on a reply, with an optional reason; a null {@code rating} takes
   * it back. In a thread, a rating with something to learn from is noted in the thread's guide.
   *
   * @param rating {@code up}, {@code down} or null
   */
  default void feedback(UUID userId, UUID conversationId, UUID messageId, String rating, String reason) {
    throw new UnsupportedOperationException("Feedback is not kept here");
  }

  /** How the user rated replies over the last {@code days} days. */
  default com.jizza.engine.api.dto.ReplyQualityDto quality(UUID userId, int days) {
    return new com.jizza.engine.api.dto.ReplyQualityDto(days, List.of(), List.of());
  }

  /** A thread's learning file. */
  ThreadGuideDto guide(UUID userId, UUID threadId);

  /** The user's own edit of a thread's learning file; Jizza adds to it from then on, never rewrites it. */
  ThreadGuideDto updateGuide(UUID userId, UUID threadId, String markdown);

  /** Deletes the conversation, its messages and its jobs for good. A main chat takes its threads with it. */
  void delete(UUID userId, UUID conversationId);
}
