package com.jizza.platform.usecase.chat;

import com.jizza.engine.api.chat.ChatService;
import com.jizza.engine.api.chat.JobDto;
import com.jizza.engine.api.dto.ConversationDto;
import com.jizza.engine.api.dto.ConversationSummaryDto;
import com.jizza.engine.api.dto.ThreadGuideDto;
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
  static final int MAX_GUIDE = 20_000;

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

  public ConversationSummaryDto create(UUID userId, String title, boolean threads) {
    return store.create(userId, checkedTitle(title), threads);
  }

  public ConversationSummaryDto update(UUID userId, UUID conversationId, String title, Boolean pinned,
                                       Boolean archived, Boolean threads) {
    if (title != null && title.isBlank()) throw new ValidationException("A conversation needs a title");
    return store.update(userId, conversationId, checkedTitle(title), pinned, archived, threads);
  }

  /** A conversation to keep outside Jizza: Markdown to read, or JSON with every message's details. */
  public Export export(UUID userId, UUID conversationId, String format) {
    ConversationSummaryDto row = store.summary(userId, conversationId);
    List<ConversationDto.MessageDto> all = store.messages(userId, conversationId).stream()
        .filter(m -> m.content() != null && !m.content().isBlank()).toList();
    String title = row.title() == null || row.title().isBlank() ? "Conversation" : row.title();
    String file = fileName(title);
    if ("json".equalsIgnoreCase(format)) {
      var node = com.jizza.engine.api.util.Json.obj()
          .put("id", conversationId.toString()).put("title", title)
          .put("createdAt", String.valueOf(row.createdAt()));
      node.set("messages", com.jizza.engine.api.util.Json.toNode(all));
      return new Export(file + ".json", "application/json", com.jizza.engine.api.util.Json.write(node));
    }
    if (format != null && !format.isBlank() && !"md".equalsIgnoreCase(format)) {
      throw new ValidationException("Export as md or json");
    }
    java.time.format.DateTimeFormatter when = java.time.format.DateTimeFormatter
        .ofPattern("d MMM yyyy, HH:mm 'UTC'", java.util.Locale.ENGLISH).withZone(java.time.ZoneOffset.UTC);
    StringBuilder md = new StringBuilder("# ").append(title).append("\n\n");
    if (row.createdAt() != null) md.append("_Started ").append(when.format(row.createdAt())).append("_\n\n");
    for (ConversationDto.MessageDto m : all) {
      switch (m.role()) {
        case "user" -> md.append("**You** · ");
        case "assistant" -> md.append("**").append(speaker(m)).append("** · ");
        default -> md.append("_");
      }
      md.append(m.createdAt() == null ? "" : when.format(m.createdAt()));
      if ("event".equals(m.role())) {
        md.append(": ").append(m.content().strip()).append("_\n\n");
      } else {
        md.append("\n\n").append(m.content().strip()).append("\n\n");
      }
    }
    return new Export(file + ".md", "text/markdown; charset=utf-8", md.toString());
  }

  public record Export(String fileName, String contentType, String body) {}

  /** Who wrote a reply: a thread's lead, or Jizza. */
  static String speaker(ConversationDto.MessageDto m) {
    if (m.metadata() == null) return "Jizza";
    String name = m.metadata().path("lead").path("name").asText("");
    return name.isBlank() ? "Jizza" : name;
  }

  static String fileName(String title) {
    String slug = title.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
        .replaceAll("(^-+|-+$)", "");
    if (slug.length() > 60) slug = slug.substring(0, 60).replaceAll("-+$", "");
    return slug.isEmpty() ? "conversation" : slug;
  }

  public ConversationSummaryDto summary(UUID userId, UUID conversationId) {
    return store.summary(userId, conversationId);
  }

  public List<ConversationSummaryDto> allThreads(UUID userId, int limit) {
    return store.allThreads(userId, Math.max(1, Math.min(limit, 200)));
  }

  public List<ConversationSummaryDto> threads(UUID userId, UUID conversationId) {
    return store.threads(userId, conversationId);
  }

  public ThreadGuideDto guide(UUID userId, UUID threadId) {
    return store.guide(userId, threadId);
  }

  public ThreadGuideDto updateGuide(UUID userId, UUID threadId, String markdown) {
    if (markdown == null) throw new ValidationException("The guide is missing");
    if (markdown.length() > MAX_GUIDE) {
      throw new ValidationException("Keep the guide under " + MAX_GUIDE + " characters");
    }
    return store.updateGuide(userId, threadId, markdown);
  }

  /** The thread's learning file, to keep or share outside Jizza. */
  public Export guideFile(UUID userId, UUID threadId) {
    ThreadGuideDto guide = store.guide(userId, threadId);
    String title = guide.title() == null || guide.title().isBlank() ? "thread" : guide.title();
    String body = guide.markdown() == null ? "# " + title + "\n" : guide.markdown();
    return new Export(fileName(title) + "-guide.md", "text/markdown; charset=utf-8", body);
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
