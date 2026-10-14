package com.jizza.engine.persistence.repository;

import com.jizza.engine.persistence.entity.MemoryHistoryEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemoryHistoryRepository extends JpaRepository<MemoryHistoryEntity, UUID> {

  List<MemoryHistoryEntity> findByMemoryIdOrderByCreatedAtAsc(UUID memoryId);
}
