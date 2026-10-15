package com.jizza.platform.usecase.chat;

import com.jizza.engine.api.chat.ChatService;
import com.jizza.engine.api.chat.JobDto;
import com.jizza.engine.api.dto.ConversationDto;
import com.jizza.engine.api.dto.ConversationSummaryDto;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.orchestrator.ConversationStore;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The user's conversations: listing, searching, renaming, pinning, archiving and deleting them,
 * and the jobs each one ran. Ownership is enforced by the store; limits and validation live here.
 */
@Service
public class ConversationUseCase {

  private static final int DEFAULT_LIMIT = 50;
  static final int MAX_TITLE = 120;

  private final ConversationStore store;
  private final ChatService chat;

  public ConversationUseCase(ConversationStore store, ChatService chat) {
    this.store = store;
    this.chat = chat;
  }

  public List<ConversationDto> list(UUID userId, int limit) {
    return store.list(userId, Math.min(limit, DEFAULT_LIMIT));
  }

  public List<ConversationSummaryDto> summaries(UUID userId, int limit, boolean archived, String query) {
    String q = query == null ? null : query.trim();
    if (q != null && q.length() > 200) q = q.substring(0, 200);
    return store.summaries(userId, Math.max(1, Math.min(limit, DEFAULT_LIMIT)), archived, q);
  }

  public ConversationDto get(UUID userId, UUID conversationId) {
    return store.get(userId, conversationId);
  }

  public List<ConversationDto.MessageDto> messages(UUID userId, UUID conversationId) {
    return store.messages(userId, conversationId);
  }

  public ConversationSummaryDto create(UUID userId, String title) {
    return store.create(userId, checkedTitle(title));
  }

  public ConversationSummaryDto update(UUID userId, UUID conversationId, String title, Boolean pinned,
                                       Boolean archived) {
    if (title != null && title.isBlank()) throw new ValidationException("A conversation needs a title");
    return store.update(userId, conversationId, checkedTitle(title), pinned, archived);
  }

  public void delete(UUID userId, UUID conversationId) {
    store.delete(userId, conversationId);
  }

  public List<JobDto> jobs(UUID userId, UUID conversationId) {
    return chat.jobs(userId, conversationId);
  }

  private static String checkedTitle(String title) {
    if (title == null) return null;
    String trimmed = title.trim();
    if (trimmed.length() > MAX_TITLE) {
      throw new ValidationException("Keep the title under " + MAX_TITLE + " characters");
    }
    return trimmed;
  }
}
