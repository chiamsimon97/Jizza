package com.jizza.engine.persistence.repository;

import com.jizza.engine.api.enums.ConnectionStatus;
import com.jizza.engine.persistence.entity.ConnectionEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConnectionRepository extends JpaRepository<ConnectionEntity, UUID> {
  List<ConnectionEntity> findByUserIdOrderByServiceNameAsc(UUID userId);
  List<ConnectionEntity> findByWorkspaceIdOrderByServiceNameAsc(UUID workspaceId);
  List<ConnectionEntity> findByStatusAndAutoRefreshTrue(ConnectionStatus status);
}
