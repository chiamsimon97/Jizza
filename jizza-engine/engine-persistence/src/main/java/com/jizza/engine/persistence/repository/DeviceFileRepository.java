package com.jizza.engine.persistence.repository;

import com.jizza.engine.persistence.entity.DeviceFileEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeviceFileRepository extends JpaRepository<DeviceFileEntity, UUID> {
  List<DeviceFileEntity> findByCommandIdOrderByCreatedAtAsc(UUID commandId);
}
