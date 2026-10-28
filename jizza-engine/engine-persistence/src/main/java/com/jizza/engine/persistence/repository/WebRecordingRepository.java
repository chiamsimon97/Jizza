package com.jizza.engine.persistence.repository;

import com.jizza.engine.persistence.entity.WebRecordingEntity;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface WebRecordingRepository extends JpaRepository<WebRecordingEntity, UUID> {

  List<WebRecordingEntity> findByOwnerIdOrderByUpdatedAtDesc(UUID ownerId);

  List<WebRecordingEntity> findByOwnerIdAndStatusInOrderByUpdatedAtDesc(
      UUID ownerId, Collection<String> statuses);

  List<WebRecordingEntity> findByStatus(String status);

  List<WebRecordingEntity> findByStatusAndUpdatedAtBefore(String status, Instant before);

  /** Serialises the events of one recording so step counts never race. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select r from WebRecordingEntity r where r.id = :id")
  Optional<WebRecordingEntity> lockById(UUID id);
}
