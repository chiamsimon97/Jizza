package com.jizza.engine.persistence.repository;

import com.jizza.engine.persistence.entity.ConversationEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConversationRepository extends JpaRepository<ConversationEntity, UUID> {
  List<ConversationEntity> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

  /** A main chat's threads, most recently active first. */
  List<ConversationEntity> findByParentIdOrderByLastMessageAtDesc(UUID parentId);

  /** The user's threads that are not archived, most recently active first. */
  @org.springframework.data.jpa.repository.Query("select c from ConversationEntity c"
      + " where c.userId = :userId and c.kind = 'thread' and c.archivedAt is null"
      + " order by coalesce(c.lastMessageAt, c.createdAt) desc")
  List<ConversationEntity> findOpenThreads(
      @org.springframework.data.repository.query.Param("userId") UUID userId, Pageable pageable);
}
