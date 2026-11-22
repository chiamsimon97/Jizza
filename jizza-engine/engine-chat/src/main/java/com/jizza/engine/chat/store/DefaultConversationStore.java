package com.jizza.engine.chat.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.dto.ConversationDto;
import com.jizza.engine.api.dto.ConversationSummaryDto;
import com.jizza.engine.api.dto.LearnedFactDto;
import com.jizza.engine.api.dto.ReplyQualityDto;
import com.jizza.engine.api.dto.ThreadGuideDto;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.enums.MessageRole;
import com.jizza.engine.api.error.ConflictException;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.orchestrator.ConversationStore;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.chat.config.ChatProperties;
import com.jizza.engine.chat.thread.ThreadGuides;
import com.jizza.engine.persistence.entity.AgentEntity;
import com.jizza.engine.persistence.entity.ConversationEntity;
import com.jizza.engine.persistence.entity.MessageEntity;
import com.jizza.engine.persistence.repository.AgentRepository;
import com.jizza.engine.persistence.repository.ConversationRepository;
import com.jizza.engine.persistence.repository.MessageRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
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
             c.message_count, c.kind, c.parent_id, c.topic, c.threads_enabled,
             coalesce(o.id, j.id) as lead_id, coalesce(o.key, j.key) as lead_key,
             coalesce(o.name, j.name) as lead_name, last.content as preview,
             case
               when c.archived_at is not null then 'resolved'
               when exists (select 1 from jobs b where b.conversation_id = c.id and b.status = 'running')
                 then 'working'
               when exists (select 1 from jobs b where b.conversation_id = c.id and b.status = 'awaiting_user')
                 or (last.role = 'assistant' and rtrim(last.content) like '%?') then 'waiting'
               else 'idle'
             end as stage
      from conversations c
      left join agents o on o.id = c.owner_agent_id
      left join agents j on c.kind = 'thread' and c.owner_agent_id is null
                        and j.user_id = c.user_id and j.is_orchestrator
      left join lateral (
        select m.content, m.role from messages m
        where m.conversation_id = c.id and m.role <> 'event' and m.content <> ''
        order by m.created_at desc limit 1) last on true
      """;

  private final ConversationRepository conversations;
  private final MessageRepository messages;
  private final JdbcTemplate jdbc;
  private final AuditLog audit;
  private final AgentRepository agents;
  private final ChatProperties props;

  DefaultConversationStore(ConversationRepository conversations, MessageRepository messages,
                           JdbcTemplate jdbc, AuditLog audit, AgentRepository agents,
                           ChatProperties props) {
    this.conversations = conversations;
    this.messages = messages;
    this.jdbc = jdbc;
    this.audit = audit;
    this.agents = agents;
    this.props = props;
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
   * match as typed) or by substring. Threads are reached from the chat they were opened in, so
   * the list shows them only in search results.
   */
  @Override
  @Transactional(readOnly = true)
  public List<ConversationSummaryDto> summaries(UUID userId, int limit, boolean archived, String query) {
    StringBuilder sql = new StringBuilder(ROW)
        .append(" where c.user_id = ? and c.message_count > 0 and c.archived_at is");
    sql.append(archived ? " not null" : " null");
    List<Object> args = new ArrayList<>(List.of(userId));
    boolean searching = query != null && !query.isBlank();
    if (!searching) sql.append(" and c.kind = 'main'");
    if (searching) {
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
  public com.jizza.engine.api.dto.EarlierSummaryDto earlier(UUID userId, UUID conversationId) {
    ConversationEntity entity = owned(userId, conversationId);
    if (entity.getSummary() == null || entity.getSummary().isBlank() || entity.getSummaryThrough() == null) {
      return null;
    }
    return new com.jizza.engine.api.dto.EarlierSummaryDto(entity.getSummary(), entity.getSummaryThrough());
  }

  @Override
  @Transactional(readOnly = true)
  public List<LearnedFactDto> learned(UUID userId, UUID conversationId) {
    owned(userId, conversationId);
    // Restricted facts are shown only after a fresh step-up, on the Memory page.
    return jdbc.query("""
            select mem.id, mem.content, mem.category, mem.confirmed, mem.created_at
            from memories mem
            join messages m on mem.source_ref = 'message:' || cast(m.id as varchar)
            where m.conversation_id = ? and mem.user_id = ? and mem.status = 'active'
              and not mem.restricted
            order by mem.created_at desc limit 100
            """,
        (rs, i) -> new LearnedFactDto(rs.getObject("id", UUID.class), rs.getString("content"),
            rs.getString("category"), rs.getBoolean("confirmed"), instant(rs.getTimestamp("created_at"))),
        conversationId, userId);
  }

  @Override
  @Transactional
  public void rewind(UUID userId, UUID conversationId, UUID messageId) {
    ConversationEntity entity = owned(userId, conversationId);
    MessageEntity from = messages.findById(messageId)
        .filter(m -> m.getConversationId().equals(conversationId) && m.getRole() == MessageRole.USER)
        .orElseThrow(() -> new NotFoundException("Message", messageId));
    Boolean busy = jdbc.queryForObject("select exists (select 1 from jobs where conversation_id = ?"
        + " and status = 'running')", Boolean.class, conversationId);
    if (Boolean.TRUE.equals(busy)) {
      throw new ConflictException("Wait for the reply in progress to finish, or stop it, before editing.");
    }
    int removed = jdbc.update("delete from messages where conversation_id = ? and created_at >= ?",
        conversationId, Timestamp.from(from.getCreatedAt()));
    entity.setMessageCount(Math.max(0, entity.getMessageCount() - removed));
    // A summary written over what was removed would bring it back into the model's view.
    if (entity.getSummaryThrough() != null && !entity.getSummaryThrough().isBefore(from.getCreatedAt())) {
      entity.setSummary(null);
      entity.setSummaryThrough(null);
    }
    audit.record(userId, ActorType.USER, userId, "chat.message.edit", conversationId.toString(),
        Json.obj().put("removed", removed));
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

  /** How many rated replies the quality list reads, and how many it shows. */
  static final int MAX_RATED = 1_000;
  static final int RECENT_RATED = 20;
  static final int MAX_REASON = 500;
  static final String FEEDBACK = "feedback";
  private static final String REMOVED_AGENT = "An agent since removed";

  @Override
  @Transactional
  public void feedback(UUID userId, UUID conversationId, UUID messageId, String rating, String reason) {
    ConversationEntity conversation = owned(userId, conversationId);
    MessageEntity reply = messages.findById(messageId)
        .filter(m -> m.getConversationId().equals(conversationId) && m.getRole() == MessageRole.ASSISTANT)
        .orElseThrow(() -> new NotFoundException("Message", messageId));
    String why = reason == null ? "" : reason.strip().replaceAll("\\s+", " ");
    if (why.length() > MAX_REASON) why = why.substring(0, MAX_REASON);
    ObjectNode meta = reply.getMetadata() instanceof ObjectNode o ? o.deepCopy() : Json.obj();
    JsonNode before = meta.get(FEEDBACK);
    if (rating == null) {
      meta.remove(FEEDBACK);
    } else {
      ObjectNode feedback = Json.obj().put("rating", rating).put("at", Instant.now().toString());
      if (!why.isEmpty()) feedback.put("reason", why);
      meta.set(FEEDBACK, feedback);
    }
    reply.setMetadata(meta);
    messages.save(reply);
    boolean changed = rating != null && (before == null || !rating.equals(before.path("rating").asText())
        || !why.equals(before.path("reason").asText("")));
    if (changed && conversation.isThread()) noteInGuide(conversation, rating, why, reply.getContent());
    audit.record(userId, ActorType.USER, userId, "chat.feedback", messageId.toString(),
        Json.obj().put("rating", rating == null ? "none" : rating).put("reason", !why.isEmpty()));
  }

  /**
   * What a rating teaches the thread's lead, under "How to work here": a thumbs down always (with
   * the reason, or the reply's opening words), a thumbs up only with a reason. A full guide is left
   * as it is; the next update condenses it.
   */
  private void noteInGuide(ConversationEntity thread, String rating, String reason, String reply) {
    String note;
    if ("down".equals(rating)) {
      note = reason.isEmpty()
          ? "The user marked a reply as unhelpful: \"" + excerpt(reply, 120) + "\""
          : "The user marked a reply as unhelpful: " + reason;
    } else if (!reason.isEmpty()) {
      note = "The user liked a reply: " + reason;
    } else {
      return;
    }
    String updated = ThreadGuides.append(thread.getGuide(), ThreadGuides.HOW, note);
    if (updated.length() > props.getThreads().getMaxGuideChars() * 2) return;
    thread.setGuide(updated);
    thread.setGuideUpdatedAt(Instant.now());
  }

  static String excerpt(String text, int max) {
    String flat = text == null ? "" : text.strip().replaceAll("\\s+", " ");
    return flat.length() <= max ? flat : flat.substring(0, max - 1).stripTrailing() + "…";
  }

  @Override
  @Transactional(readOnly = true)
  public ReplyQualityDto quality(UUID userId, int days) {
    Instant since = Instant.now().minus(Duration.ofDays(days));
    List<RatedRow> rows = jdbc.query("""
        select m.id, m.conversation_id, c.title, m.content,
               m.metadata->'feedback'->>'rating' as rating, m.metadata->'feedback'->>'reason' as reason,
               m.metadata->'feedback'->>'at' as at, m.metadata->'lead'->>'agentId' as lead_id
        from messages m join conversations c on c.id = m.conversation_id
        where c.user_id = ? and m.role = 'assistant' and m.metadata->'feedback' is not null
          and m.created_at >= ?
        order by m.metadata->'feedback'->>'at' desc
        limit ?""",
        (rs, i) -> new RatedRow(rs.getObject("id", UUID.class), rs.getObject("conversation_id", UUID.class),
            rs.getString("title"), rs.getString("content"), rs.getString("rating"), rs.getString("reason"),
            parseInstant(rs.getString("at")), parseUuid(rs.getString("lead_id"))),
        userId, Timestamp.from(since), MAX_RATED);
    return rated(days, rows, agents.findByUserIdAndIsOrchestratorTrue(userId).orElse(null),
        ownedAgents(userId, rows));
  }

  private Map<UUID, AgentEntity> ownedAgents(UUID userId, List<RatedRow> rows) {
    Map<UUID, AgentEntity> byId = new HashMap<>();
    agents.findAllById(rows.stream().map(RatedRow::leadId).filter(Objects::nonNull).collect(Collectors.toSet()))
        .forEach(a -> {
          if (a.getUserId().equals(userId)) byId.put(a.getId(), a);
        });
    return byId;
  }

  /**
   * Counts each agent's thumbs up and down, those with most thumbs down first, and keeps the
   * latest rated replies. A reply with no lead was Jizza's own.
   */
  static ReplyQualityDto rated(int days, List<RatedRow> rows, AgentEntity jizza, Map<UUID, AgentEntity> byId) {
    Map<UUID, int[]> counts = new LinkedHashMap<>();
    Map<UUID, AgentEntity> seen = new HashMap<>();
    List<ReplyQualityDto.Rated> recent = new ArrayList<>();
    for (RatedRow row : rows) {
      AgentEntity agent = row.leadId() == null ? jizza : byId.get(row.leadId());
      UUID id = agent == null ? null : agent.getId();
      if (agent != null) seen.put(id, agent);
      int[] c = counts.computeIfAbsent(id, k -> new int[2]);
      if ("up".equals(row.rating())) c[0]++;
      else if ("down".equals(row.rating())) c[1]++;
      if (recent.size() < RECENT_RATED) {
        recent.add(new ReplyQualityDto.Rated(row.conversationId(), row.messageId(), row.title(),
            agent == null ? null : agent.getKey(), agent == null ? REMOVED_AGENT : agent.getName(),
            row.rating(), row.reason(), excerpt(row.content(), 160), row.at()));
      }
    }
    List<ReplyQualityDto.AgentQuality> perAgent = new ArrayList<>();
    counts.forEach((id, c) -> {
      AgentEntity agent = id == null ? null : seen.get(id);
      perAgent.add(new ReplyQualityDto.AgentQuality(id, agent == null ? null : agent.getKey(),
          agent == null ? REMOVED_AGENT : agent.getName(), c[0], c[1]));
    });
    perAgent.sort(Comparator.comparingInt(ReplyQualityDto.AgentQuality::down).reversed()
        .thenComparing(ReplyQualityDto.AgentQuality::name));
    return new ReplyQualityDto(days, perAgent, recent);
  }

  record RatedRow(UUID messageId, UUID conversationId, String title, String content,
                  String rating, String reason, Instant at, UUID leadId) {}

  private static Instant parseInstant(String s) {
    try {
      return s == null ? null : Instant.parse(s);
    } catch (java.time.format.DateTimeParseException e) {
      return null;
    }
  }

  private static UUID parseUuid(String s) {
    try {
      return s == null || s.isBlank() ? null : UUID.fromString(s);
    } catch (IllegalArgumentException e) {
      return null;
    }
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
    owned(userId, conversationId);
    return jdbc.query(ROW + " where c.id = ?", (rs, i) -> summary(rs), conversationId).get(0);
  }

  @Override
  @Transactional
  public ConversationSummaryDto create(UUID userId, String title, boolean threads) {
    ConversationEntity entity = new ConversationEntity();
    entity.setUserId(userId);
    entity.setThreadsEnabled(threads);
    if (title != null && !title.isBlank()) {
      entity.setTitle(title.trim());
      entity.setTitleSource("user");
    }
    return summary(conversations.save(entity), null);
  }

  @Override
  @Transactional
  public ConversationSummaryDto update(UUID userId, UUID conversationId, String title,
                                       Boolean pinned, Boolean archived, Boolean threads) {
    ConversationEntity entity = owned(userId, conversationId);
    if (threads != null && !entity.isThread()) entity.setThreadsEnabled(threads);
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
        rs.getString("topic"), rs.getBoolean("threads_enabled"), rs.getString("stage"));
  }

  private ConversationSummaryDto summary(ConversationEntity c, String preview) {
    return new ConversationSummaryDto(c.getId(), c.getTitle(), preview, c.getCreatedAt(),
        c.getLastMessageAt(), c.isPinned(), c.getArchivedAt() != null, c.getMessageCount(),
        c.getKind(), c.getParentId(), leadOf(c), c.getTopic(), c.isThreadsEnabled(),
        c.getArchivedAt() != null ? ConversationSummaryDto.RESOLVED : ConversationSummaryDto.IDLE);
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
