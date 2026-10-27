package com.jizza.engine.persistence.repository;

import com.jizza.engine.persistence.entity.DeviceCommandEntity;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface DeviceCommandRepository extends JpaRepository<DeviceCommandEntity, UUID> {
  Optional<DeviceCommandEntity> findByIdempotencyKey(String idempotencyKey);
  List<DeviceCommandEntity> findByDeviceIdAndStatusInOrderByInteractiveDescCreatedAtAsc(
      UUID deviceId, Collection<String> statuses);
  long countByDeviceIdAndStatusIn(UUID deviceId, Collection<String> statuses);
  List<DeviceCommandEntity> findByRunIdAndStatusIn(UUID runId, Collection<String> statuses);
  List<DeviceCommandEntity> findByUserIdAndStatusIn(UUID userId, Collection<String> statuses);
  List<DeviceCommandEntity> findByDeviceIdOrderByCreatedAtDesc(UUID deviceId, Pageable page);
  long countByWorkspaceIdAndCreatedAtAfter(UUID workspaceId, Instant after);

  @Query("select c from DeviceCommandEntity c where c.status in :statuses and c.deadlineAt < :now")
  List<DeviceCommandEntity> findOverdue(Collection<String> statuses, Instant now);
}
