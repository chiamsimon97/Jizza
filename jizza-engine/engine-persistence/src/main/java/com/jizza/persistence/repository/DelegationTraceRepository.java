package com.jizza.persistence.repository;

import com.jizza.persistence.entity.DelegationTraceEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DelegationTraceRepository extends JpaRepository<DelegationTraceEntity, UUID> {
  List<DelegationTraceEntity> findByMessageId(UUID messageId);
}
