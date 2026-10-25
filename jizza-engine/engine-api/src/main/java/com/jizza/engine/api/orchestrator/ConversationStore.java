package com.jizza.engine.api.orchestrator;

import com.jizza.engine.api.dto.ConversationDto;
import com.jizza.engine.api.dto.ConversationSummaryDto;
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

  ConversationSummaryDto create(UUID userId, String title);

  /** One conversation's list row: its title, pin and counts. */
  ConversationSummaryDto summary(UUID userId, UUID conversationId);

  /** Any argument left null is unchanged. A title set here is the user's and is never replaced automatically. */
  ConversationSummaryDto update(UUID userId, UUID conversationId, String title, Boolean pinned,
                                Boolean archived);

  /** A main chat's threads, most recently active first, archived ones included. */
  List<ConversationSummaryDto> threads(UUID userId, UUID conversationId);

  /** A thread's learning file. */
  ThreadGuideDto guide(UUID userId, UUID threadId);

  /** The user's own edit of a thread's learning file; Jizza adds to it from then on, never rewrites it. */
  ThreadGuideDto updateGuide(UUID userId, UUID threadId, String markdown);

  /** Deletes the conversation, its messages and its jobs for good. A main chat takes its threads with it. */
  void delete(UUID userId, UUID conversationId);
}
