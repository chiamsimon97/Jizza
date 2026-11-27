package com.jizza.engine.persistence.support;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the memory review reads about one user's recent activity: what they said in chats, which
 * tool calls failed in their jobs, and which workflow steps failed in their runs. Every query is
 * scoped by the user in its {@code where} clause. Incognito turns leave nothing in the database, so
 * none of it is ever read here.
 */
@Component
public class MemoryReviewQueries {

  @PersistenceContext
  private EntityManager em;

  /** Something the user typed in a chat (never the assistant's reply). */
  public record UserMessage(UUID messageId, UUID conversationId, String content, Instant at) {}

  /** A tool an agent called in one of the user's jobs that came back with an error. */
  public record FailedToolCall(UUID jobId, UUID conversationId, String jobTitle, UUID agentId,
                               String agentName, String tool, String label, String error,
                               Instant at) {}

  /** A workflow step that failed in one of the user's runs. */
  public record FailedStep(UUID runId, UUID workflowId, String workflowName, UUID owningAgentId,
                           String nodeId, String nodeLabel, String nodeType, String url,
                           String error, Instant at) {}

  @Transactional(readOnly = true)
  @SuppressWarnings("unchecked")
  public List<UserMessage> userMessages(UUID userId, Instant since, Instant until, int limit) {
    List<Object[]> rows = em.createNativeQuery("""
            select m.id, m.conversation_id, m.content, m.created_at
              from messages m
              join conversations c on c.id = m.conversation_id
             where c.user_id = :userId and m.role = 'user'
               and m.created_at >= :since and m.created_at < :until
             order by m.created_at desc
             limit :limit
            """)
        .setParameter("userId", userId)
        .setParameter("since", since)
        .setParameter("until", until)
        .setParameter("limit", limit)
        .getResultList();
    List<UserMessage> out = new ArrayList<>();
    for (Object[] r : rows) {
      out.add(new UserMessage((UUID) r[0], (UUID) r[1], (String) r[2], instant(r[3])));
    }
    return out.reversed();
  }

  @Transactional(readOnly = true)
  @SuppressWarnings("unchecked")
  public List<FailedToolCall> failedToolCalls(UUID userId, Instant since, Instant until, int limit) {
    List<Object[]> rows = em.createNativeQuery("""
            select j.id, j.conversation_id, j.title, e.agent_id, e.agent_name,
                   e.payload ->> 'tool', e.payload ->> 'label', e.payload ->> 'error', e.at
              from job_events e
              join jobs j on j.id = e.job_id
             where j.user_id = :userId and e.type = 'tool.call'
               and e.payload ->> 'state' = 'failed'
               and e.at >= :since and e.at < :until
             order by e.at desc
             limit :limit
            """)
        .setParameter("userId", userId)
        .setParameter("since", since)
        .setParameter("until", until)
        .setParameter("limit", limit)
        .getResultList();
    List<FailedToolCall> out = new ArrayList<>();
    for (Object[] r : rows) {
      out.add(new FailedToolCall((UUID) r[0], (UUID) r[1], (String) r[2], (UUID) r[3],
          (String) r[4], (String) r[5], (String) r[6], (String) r[7], instant(r[8])));
    }
    return out.reversed();
  }

  @Transactional(readOnly = true)
  @SuppressWarnings("unchecked")
  public List<FailedStep> failedSteps(UUID userId, Instant since, Instant until, int limit) {
    List<Object[]> rows = em.createNativeQuery("""
            select r.id, w.id, w.name, w.owning_agent_id, s.node_id,
                   n.node ->> 'label', n.node ->> 'type',
                   coalesce(n.node -> 'config' -> 'inputs' ->> 'url',
                            n.node -> 'config' -> 'inputs' ->> 'expectOrigin'),
                   coalesce(s.error, s.output ->> 'error', s.output ->> 'message'),
                   coalesce(s.finished_at, s.started_at)
              from workflow_run_steps s
              join workflow_runs r on r.id = s.run_id
              join workflows w on w.id = r.workflow_id
              left join lateral (
                select x as node from jsonb_array_elements(
                    case when jsonb_typeof(w.graph -> 'nodes') = 'array' then w.graph -> 'nodes'
                         else '[]'::jsonb end) x
                 where x ->> 'id' = s.node_id
                 limit 1) n on true
             where r.user_id = :userId and s.status = 'failed'
               and coalesce(s.finished_at, s.started_at) >= :since
               and coalesce(s.finished_at, s.started_at) < :until
             order by coalesce(s.finished_at, s.started_at) desc
             limit :limit
            """)
        .setParameter("userId", userId)
        .setParameter("since", since)
        .setParameter("until", until)
        .setParameter("limit", limit)
        .getResultList();
    List<FailedStep> out = new ArrayList<>();
    for (Object[] r : rows) {
      out.add(new FailedStep((UUID) r[0], (UUID) r[1], (String) r[2], (UUID) r[3], (String) r[4],
          (String) r[5], (String) r[6], (String) r[7], (String) r[8], instant(r[9])));
    }
    return out.reversed();
  }

  /**
   * Users with something for the review to read since {@code since}: a chat message, a job or a
   * workflow run, who have the review switched on (no settings row means the default, on).
   */
  @Transactional(readOnly = true)
  @SuppressWarnings("unchecked")
  public List<UUID> activeUsers(Instant since) {
    return em.createNativeQuery("""
            select u.id from users u
             where not exists (select 1 from memory_settings s
                                where s.user_id = u.id and s.review_enabled = false)
               and (exists (select 1 from jobs j where j.user_id = u.id and j.started_at >= :since)
                    or exists (select 1 from workflow_runs r
                                where r.user_id = u.id and r.created_at >= :since))
            """)
        .setParameter("since", since)
        .getResultList();
  }

  private static Instant instant(Object value) {
    if (value == null) return null;
    if (value instanceof Instant i) return i;
    if (value instanceof OffsetDateTime o) return o.toInstant();
    if (value instanceof Timestamp t) return t.toInstant();
    if (value instanceof java.time.ZonedDateTime z) return z.toInstant();
    if (value instanceof java.time.LocalDateTime l) return l.toInstant(java.time.ZoneOffset.UTC);
    throw new IllegalStateException("Unexpected timestamp type " + value.getClass());
  }
}
