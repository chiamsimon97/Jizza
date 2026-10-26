package com.jizza.engine.persistence.repository;

import com.jizza.engine.persistence.entity.MessageEntity;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MessageRepository extends JpaRepository<MessageEntity, UUID> {

  List<MessageEntity> findByConversationIdOrderByCreatedAtAsc(UUID conversationId);

  List<MessageEntity> findByConversationIdOrderByCreatedAtDesc(UUID conversationId, Pageable pageable);

  /** The newest messages after a point, for the model's window. */
  List<MessageEntity> findByConversationIdAndCreatedAtAfterOrderByCreatedAtDesc(UUID conversationId,
                                                                               Instant after,
                                                                               Pageable pageable);

  /** Everything not yet folded into the conversation's summary, oldest first. */
  List<MessageEntity> findByConversationIdAndCreatedAtAfterOrderByCreatedAtAsc(UUID conversationId,
                                                                              Instant after);

  java.util.Optional<MessageEntity> findByConversationIdAndClientMessageId(UUID conversationId,
                                                                           UUID clientMessageId);

  /**
   * Of these messages, the ones written in a thread other than {@code current}: a fact learned
   * from one of them belongs to that thread's memory.
   */
  @Query(value = "select cast(m.id as varchar) from messages m"
      + " join conversations c on c.id = m.conversation_id"
      + " where m.id in (:ids) and c.kind = 'thread' and c.id <> :current", nativeQuery = true)
  List<String> inOtherThreads(@Param("ids") java.util.Collection<UUID> ids,
                              @Param("current") UUID current);

  /** Replies left mid-stream by a process that stopped. */
  @Modifying
  @Query("update MessageEntity m set m.status = 'failed' where m.status = 'streaming'")
  int failAbandoned();

  @Modifying
  @Query("delete from MessageEntity m where m.createdAt < :cutoff")
  int pruneOlderThan(@Param("cutoff") Instant cutoff);
}
