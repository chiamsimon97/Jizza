package com.jizza.persistence.repository;

import com.jizza.persistence.entity.AuditLogEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditLogRepository extends JpaRepository<AuditLogEntity, UUID> {

  List<AuditLogEntity> findByUserIdOrderByCreatedAtAsc(UUID userId);

  List<AuditLogEntity> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

  Optional<AuditLogEntity> findFirstByUserIdOrderByCreatedAtDescIdDesc(UUID userId);
}
