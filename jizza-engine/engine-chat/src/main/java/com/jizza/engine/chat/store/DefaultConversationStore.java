package com.jizza.engine.chat.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.dto.ConversationDto;
import com.jizza.engine.api.dto.ConversationSummaryDto;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.orchestrator.ConversationStore;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.persistence.entity.ConversationEntity;
import com.jizza.engine.persistence.entity.MessageEntity;
import com.jizza.engine.persistence.repository.ConversationRepository;
import com.jizza.engine.persistence.repository.MessageRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class DefaultConversationStore implements ConversationStore {

  private final ConversationRepository conversations;
  private final MessageRepository messages;
  private final JdbcTemplate jdbc;
  private final AuditLog audit;

  DefaultConversationStore(ConversationRepository conversations, MessageRepository messages,
                           JdbcTemplate jdbc, AuditLog audit) {
    this.conversations = conversations;
    this.messages = messages;
    this.jdbc = jdbc;
    this.audit = audit;
  }

  @Override
  @Transactional(readOnly = true)
  public List<ConversationDto> list(UUID userId, int limit) {
    return conversations.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, limit))
        .stream()
        .map(c -> new ConversationDto(c.getId(), c.getCreatedAt(), List.of()))
        .toList();
  }

  /**
   * One query for the whole list: the latest message of each conversation is its preview. Search
   * matches the title or any message, by words ({@code simple} full-text, so names and amounts
   * match as typed) or by substring.
   */
  @Override
  @Transactional(readOnly = true)
  public List<ConversationSummaryDto> summaries(UUID userId, int limit, boolean archived, String query) {
    StringBuilder sql = new StringBuilder("""
        select c.id, c.title, c.created_at, c.last_message_at, c.pinned, c.archived_at,
               c.message_count, last.content as preview
        from conversations c
        left join lateral (
          select m.content from messages m
          where m.conversation_id = c.id and m.role <> 'event' and m.content <> ''
          order by m.created_at desc limit 1) last on true
        where c.user_id = ? and c.message_count > 0 and c.archived_at is
        """);
    sql.append(archived ? " not null" : " null");
    List<Object> args = new ArrayList<>(List.of(userId));
    if (query != null && !query.isBlank()) {
      String q = query.trim();
      sql.append("""
           and (c.title ilike ? or exists (
             select 1 from messages m where m.conversation_id = c.id
             and (to_tsvector('simple', m.content) @@ plainto_tsquery('simple', ?)
                  or m.content ilike ?)))
          """);
      String like = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
      args.add(like);
      args.add(q);
      args.add(like);
    }
    sql.append(" order by c.pinned desc, coalesce(c.last_message_at, c.created_at) desc limit ?");
    args.add(limit);
    return jdbc.query(sql.toString(), (rs, i) -> summary(rs), args.toArray());
  }

  @Override
  @Transactional(readOnly = true)
  public ConversationDto get(UUID userId, UUID conversationId) {
    ConversationEntity entity = owned(userId, conversationId);
    return new ConversationDto(entity.getId(), entity.getCreatedAt(),
        toMessageDtos(messages.findByConversationIdOrderByCreatedAtAsc(conversationId)));
  }

  @Override
  @Transactional(readOnly = true)
  public List<ConversationDto.MessageDto> messages(UUID userId, UUID conversationId) {
    owned(userId, conversationId);
    return toMessageDtos(messages.findByConversationIdOrderByCreatedAtAsc(conversationId));
  }

  @Override
  @Transactional(readOnly = true)
  public ConversationSummaryDto summary(UUID userId, UUID conversationId) {
    return summary(owned(userId, conversationId), null);
  }

  @Override
  @Transactional
  public ConversationSummaryDto create(UUID userId, String title) {
    ConversationEntity entity = new ConversationEntity();
    entity.setUserId(userId);
    if (title != null && !title.isBlank()) {
      entity.setTitle(title.trim());
      entity.setTitleSource("user");
    }
    return summary(conversations.save(entity), null);
  }

  @Override
  @Transactional
  public ConversationSummaryDto update(UUID userId, UUID conversationId, String title,
                                       Boolean pinned, Boolean archived) {
    ConversationEntity entity = owned(userId, conversationId);
    if (title != null && !title.isBlank()) {
      entity.setTitle(title.trim());
      entity.setTitleSource("user");
    }
    if (pinned != null) entity.setPinned(pinned);
    if (archived != null) entity.setArchivedAt(archived ? Instant.now() : null);
    return summary(entity, null);
  }

  @Override
  @Transactional
  public void delete(UUID userId, UUID conversationId) {
    ConversationEntity entity = owned(userId, conversationId);
    conversations.delete(entity);
    // Ids and counts only: what was said is gone, and the append-only log must not keep it.
    audit.record(userId, ActorType.USER, userId, "chat.conversation.delete", conversationId.toString(),
        Json.obj().put("messages", entity.getMessageCount()));
  }

  private ConversationEntity owned(UUID userId, UUID conversationId) {
    ConversationEntity entity = conversations.findById(conversationId)
        .orElseThrow(() -> new NotFoundException("Conversation", conversationId));
    if (!entity.getUserId().equals(userId)) throw new NotFoundException("Conversation", conversationId);
    return entity;
  }

  private static ConversationSummaryDto summary(ResultSet rs) throws SQLException {
    return new ConversationSummaryDto(rs.getObject("id", UUID.class), rs.getString("title"),
        preview(rs.getString("preview")), instant(rs.getTimestamp("created_at")),
        instant(rs.getTimestamp("last_message_at")), rs.getBoolean("pinned"),
        rs.getTimestamp("archived_at") != null, rs.getInt("message_count"));
  }

  private static ConversationSummaryDto summary(ConversationEntity c, String preview) {
    return new ConversationSummaryDto(c.getId(), c.getTitle(), preview, c.getCreatedAt(),
        c.getLastMessageAt(), c.isPinned(), c.getArchivedAt() != null, c.getMessageCount());
  }

  private static String preview(String content) {
    if (content == null) return null;
    String oneLine = content.replaceAll("\\s+", " ").trim();
    return oneLine.length() <= 140 ? oneLine : oneLine.substring(0, 139) + "…";
  }

  private static Instant instant(Timestamp ts) { return ts == null ? null : ts.toInstant(); }

  private static List<ConversationDto.MessageDto> toMessageDtos(List<MessageEntity> entities) {
    return entities.stream()
        .map(m -> new ConversationDto.MessageDto(m.getId(), m.getRole().name().toLowerCase(),
            m.getContent(), m.getCreatedAt(), m.getStatus(), m.getJobId(), metadata(m.getMetadata())))
        .toList();
  }

  private static JsonNode metadata(JsonNode node) {
    return node == null || node.isEmpty() ? null : node;
  }
}
