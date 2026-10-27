package com.jizza.engine.persistence.repository;

import com.jizza.engine.persistence.entity.DeviceEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeviceRepository extends JpaRepository<DeviceEntity, UUID> {
  List<DeviceEntity> findByWorkspaceIdOrderByCreatedAtAsc(UUID workspaceId);
  List<DeviceEntity> findByOwnerUserIdAndWorkspaceId(UUID ownerUserId, UUID workspaceId);
  List<DeviceEntity> findByStatus(String status);
  List<DeviceEntity> findByOwnerUserIdAndStatusOrderByCreatedAtAsc(UUID ownerUserId, String status);
}
