package com.jizza.engine.persistence.repository;

import com.jizza.engine.persistence.entity.KnowledgeHistoryEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KnowledgeHistoryRepository extends JpaRepository<KnowledgeHistoryEntity, UUID> {

  List<KnowledgeHistoryEntity> findByEntryIdOrderByCreatedAtAsc(UUID entryId);
}
