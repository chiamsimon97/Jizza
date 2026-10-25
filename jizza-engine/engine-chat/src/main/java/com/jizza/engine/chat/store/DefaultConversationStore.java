package com.jizza.engine.chat.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.dto.ConversationDto;
import com.jizza.engine.api.dto.ConversationSummaryDto;
import com.jizza.engine.api.dto.ThreadGuideDto;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.orchestrator.ConversationStore;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.persistence.entity.ConversationEntity;
import com.jizza.engine.persistence.entity.MessageEntity;
import com.jizza.engine.persistence.repository.AgentRepository;
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

  /**
   * A row of the list, with a thread's lead: its owner, or Jizza when it has none. The caller adds
   * the {@code where} clause.
   */
  private static final String ROW = """
      select c.id, c.title, c.created_at, c.last_message_at, c.pinned, c.archived_at,
             c.message_count, c.kind, c.parent_id, c.topic,
             coalesce(o.id, j.id) as lead_id, coalesce(o.key, j.key) as lead_key,
             coalesce(o.name, j.name) as lead_name, last.content as preview
      from conversations c
      left join agents o on o.id = c.owner_agent_id
      left join agents j on c.kind = 'thread' and c.owner_agent_id is null
                        and j.user_id = c.user_id and j.is_orchestrator
      left join lateral (
        select m.content from messages m
        where m.conversation_id = c.id and m.role <> 'event' and m.content <> ''
        order by m.created_at desc limit 1) last on true
      """;

  private final ConversationRepository conversations;
  private final MessageRepository messages;
  private final JdbcTemplate jdbc;
  private final AuditLog audit;
  private final AgentRepository agents;

  DefaultConversationStore(ConversationRepository conversations, MessageRepository messages,
                           JdbcTemplate jdbc, AuditLog audit, AgentRepository agents) {
    this.conversations = conversations;
    this.messages = messages;
    this.jdbc = jdbc;
    this.audit = audit;
    this.agents = agents;
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
   * match as typed) or by substring. Threads are listed too; the client nests them under their
   * main chat.
   */
  @Override
  @Transactional(readOnly = true)
  public List<ConversationSummaryDto> summaries(UUID userId, int limit, boolean archived, String query) {
    StringBuilder sql = new StringBuilder(ROW)
        .append(" where c.user_id = ? and c.message_count > 0 and c.archived_at is");
    sql.append(archived ? " not null" : " null");
    List<Object> args = new ArrayList<>(List.of(userId));
    if (query != null && !query.isBlank()) {
      String q = query.trim();
      sql.append("""
           and (c.title ilike ? or c.topic ilike ? or exists (
             select 1 from messages m where m.conversation_id = c.id
             and (to_tsvector('simple', m.content) @@ plainto_tsquery('simple', ?)
                  or m.content ilike ?)))
          """);
      String like = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
      args.add(like);
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
  public List<ConversationSummaryDto> threads(UUID userId, UUID conversationId) {
    owned(userId, conversationId);
    return jdbc.query(ROW + " where c.user_id = ? and c.parent_id = ?"
            + " order by coalesce(c.last_message_at, c.created_at) desc",
        (rs, i) -> summary(rs), userId, conversationId);
  }

  @Override
  @Transactional(readOnly = true)
  public ThreadGuideDto guide(UUID userId, UUID threadId) {
    return guideOf(thread(userId, threadId));
  }

  @Override
  @Transactional
  public ThreadGuideDto updateGuide(UUID userId, UUID threadId, String markdown) {
    ConversationEntity entity = thread(userId, threadId);
    entity.setGuide(markdown == null ? "" : markdown.strip() + "\n");
    entity.setGuideSource("user");
    entity.setGuideUpdatedAt(Instant.now());
    // Its size only: the guide is the user's own words about their life.
    audit.record(userId, ActorType.USER, userId, "chat.thread.guide.edit", threadId.toString(),
        Json.obj().put("chars", entity.getGuide().length()));
    return guideOf(entity);
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

  private ConversationEntity thread(UUID userId, UUID threadId) {
    ConversationEntity entity = owned(userId, threadId);
    if (!entity.isThread()) throw new NotFoundException("Thread", threadId);
    return entity;
  }

  private ThreadGuideDto guideOf(ConversationEntity c) {
    return new ThreadGuideDto(c.getId(), c.getTitle(), c.getTopic(), leadOf(c), c.getGuide(),
        c.getGuideUpdatedAt(), c.getGuideSource());
  }

  /** A thread's lead: its owner, or Jizza when it has none. Null for a main chat. */
  private ConversationSummaryDto.AgentRef leadOf(ConversationEntity c) {
    if (!c.isThread()) return null;
    var agent = c.getOwnerAgentId() != null ? agents.findById(c.getOwnerAgentId())
        : agents.findByUserIdAndIsOrchestratorTrue(c.getUserId());
    return agent.map(a -> new ConversationSummaryDto.AgentRef(a.getId(), a.getKey(), a.getName()))
        .orElse(null);
  }

  private static ConversationSummaryDto summary(ResultSet rs) throws SQLException {
    UUID leadId = rs.getObject("lead_id", UUID.class);
    return new ConversationSummaryDto(rs.getObject("id", UUID.class), rs.getString("title"),
        preview(rs.getString("preview")), instant(rs.getTimestamp("created_at")),
        instant(rs.getTimestamp("last_message_at")), rs.getBoolean("pinned"),
        rs.getTimestamp("archived_at") != null, rs.getInt("message_count"), rs.getString("kind"),
        rs.getObject("parent_id", UUID.class),
        leadId == null ? null
            : new ConversationSummaryDto.AgentRef(leadId, rs.getString("lead_key"), rs.getString("lead_name")),
        rs.getString("topic"));
  }

  private ConversationSummaryDto summary(ConversationEntity c, String preview) {
    return new ConversationSummaryDto(c.getId(), c.getTitle(), preview, c.getCreatedAt(),
        c.getLastMessageAt(), c.isPinned(), c.getArchivedAt() != null, c.getMessageCount(),
        c.getKind(), c.getParentId(), leadOf(c), c.getTopic());
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
