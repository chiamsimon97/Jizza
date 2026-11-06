package com.jizza.engine.persistence.repository;

import com.jizza.engine.persistence.entity.WebLocatorFixEntity;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface WebLocatorFixRepository extends JpaRepository<WebLocatorFixEntity, UUID> {

  List<WebLocatorFixEntity> findByOwnerIdOrderByCreatedAtDesc(UUID ownerId);

  List<WebLocatorFixEntity> findByOwnerIdAndStatusOrderByCreatedAtDesc(UUID ownerId, String status);

  Optional<WebLocatorFixEntity> findByWorkflowIdAndNodeIdAndStatus(UUID workflowId, String nodeId,
                                                                   String status);

  /** Serialises apply and dismiss, so one fix is decided once. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select f from WebLocatorFixEntity f where f.id = :id")
  Optional<WebLocatorFixEntity> lockById(UUID id);
}
