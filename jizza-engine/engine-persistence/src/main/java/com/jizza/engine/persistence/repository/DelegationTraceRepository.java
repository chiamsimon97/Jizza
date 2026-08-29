package com.jizza.engine.persistence.repository;

import com.jizza.engine.persistence.entity.DelegationTraceEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DelegationTraceRepository extends JpaRepository<DelegationTraceEntity, UUID> {
  List<DelegationTraceEntity> findByMessageId(UUID messageId);
}
