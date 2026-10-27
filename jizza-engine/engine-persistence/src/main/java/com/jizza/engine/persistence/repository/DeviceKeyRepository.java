package com.jizza.engine.persistence.repository;

import com.jizza.engine.persistence.entity.DeviceKeyEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeviceKeyRepository extends JpaRepository<DeviceKeyEntity, UUID> {
  Optional<DeviceKeyEntity> findFirstByDeviceIdAndRetiredAtIsNullOrderByCreatedAtDesc(UUID deviceId);
}
