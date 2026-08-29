package com.jizza.persistence.repository;

import com.jizza.common.enums.ConnectionStatus;
import com.jizza.persistence.entity.ConnectionEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConnectionRepository extends JpaRepository<ConnectionEntity, UUID> {
  List<ConnectionEntity> findByUserIdOrderByServiceNameAsc(UUID userId);
  List<ConnectionEntity> findByStatusAndAutoRefreshTrue(ConnectionStatus status);
}
