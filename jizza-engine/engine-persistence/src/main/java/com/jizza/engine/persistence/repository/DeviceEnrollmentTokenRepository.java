package com.jizza.engine.persistence.repository;

import com.jizza.engine.persistence.entity.DeviceEnrollmentTokenEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeviceEnrollmentTokenRepository
    extends JpaRepository<DeviceEnrollmentTokenEntity, UUID> {
  Optional<DeviceEnrollmentTokenEntity> findByTokenHash(String tokenHash);
  List<DeviceEnrollmentTokenEntity> findByWorkspaceIdOrderByCreatedAtDesc(UUID workspaceId);
}
