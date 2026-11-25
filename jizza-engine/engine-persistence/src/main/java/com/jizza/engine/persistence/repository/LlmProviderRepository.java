package com.jizza.engine.persistence.repository;

import com.jizza.engine.persistence.entity.LlmProviderEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LlmProviderRepository extends JpaRepository<LlmProviderEntity, UUID> {

  List<LlmProviderEntity> findByUserIdOrderByCreatedAtAsc(UUID userId);

  Optional<LlmProviderEntity> findByUserIdAndProvider(UUID userId, String provider);
}
