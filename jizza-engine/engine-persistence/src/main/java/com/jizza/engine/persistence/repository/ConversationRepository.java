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

}
