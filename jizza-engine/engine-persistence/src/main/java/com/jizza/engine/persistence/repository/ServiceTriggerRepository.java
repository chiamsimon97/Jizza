package com.jizza.engine.persistence.repository;

import com.jizza.engine.persistence.entity.ServiceTriggerEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ServiceTriggerRepository extends JpaRepository<ServiceTriggerEntity, UUID> {
  List<ServiceTriggerEntity> findByConnectionIdOrderByLabelAsc(UUID connectionId);

  List<ServiceTriggerEntity> findByEnabledTrue();

  java.util.Optional<ServiceTriggerEntity> findByEndpointToken(String endpointToken);
}
