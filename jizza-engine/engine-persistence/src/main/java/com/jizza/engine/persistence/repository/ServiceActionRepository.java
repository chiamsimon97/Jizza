package com.jizza.engine.persistence.repository;

import com.jizza.engine.persistence.entity.ServiceActionEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ServiceActionRepository extends JpaRepository<ServiceActionEntity, UUID> {
  List<ServiceActionEntity> findByConnectionIdOrderByLabelAsc(UUID connectionId);
}
