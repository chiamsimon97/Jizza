package com.jizza.persistence.repository;

import com.jizza.persistence.entity.OutboxEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxRepository extends JpaRepository<OutboxEntity, UUID> {
  List<OutboxEntity> findByPublishedFalseOrderByCreatedAtAsc(Pageable pageable);
}
