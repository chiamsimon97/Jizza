package com.jizza.engine.persistence.repository;

import com.jizza.engine.persistence.entity.ChatJobEntity;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChatJobRepository extends JpaRepository<ChatJobEntity, UUID> {

  List<ChatJobEntity> findByConversationIdOrderByStartedAtAsc(UUID conversationId);

  List<ChatJobEntity> findByConversationIdAndStatus(UUID conversationId, String status);

  /** Jobs left running by a process that stopped; they can never finish now. */
  @Modifying
  @Query("update ChatJobEntity j set j.status = 'failed', j.finishedAt = :now, "
      + "j.summary = 'Interrupted when Jizza restarted' where j.status = 'running'")
  int failAbandoned(@Param("now") Instant now);
}
