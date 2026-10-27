package com.jizza.engine.persistence.repository;

import com.jizza.engine.persistence.entity.DeviceLogEntity;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface DeviceLogRepository extends JpaRepository<DeviceLogEntity, Long> {
  List<DeviceLogEntity> findByDeviceIdOrderByTsDesc(UUID deviceId, Pageable page);

  @Modifying
  @Query("delete from DeviceLogEntity l where l.ts < :before")
  int deleteOlderThan(Instant before);
}
