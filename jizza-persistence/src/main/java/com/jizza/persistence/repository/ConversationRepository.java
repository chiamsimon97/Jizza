package com.jizza.persistence.repository;

import com.jizza.persistence.entity.ConversationEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConversationRepository extends JpaRepository<ConversationEntity, UUID> {
  List<ConversationEntity> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);
}
