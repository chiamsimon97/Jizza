package com.jizza.engine.persistence.repository;

import com.jizza.engine.persistence.entity.IdempotencyKeyEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKeyEntity, UUID> {
  Optional<IdempotencyKeyEntity> findByUserIdAndKey(UUID userId, String key);
}
