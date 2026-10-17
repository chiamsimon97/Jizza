package com.jizza.engine.persistence.repository;

import com.jizza.engine.persistence.entity.ServiceRunEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ServiceRunRepository extends JpaRepository<ServiceRunEntity, UUID> {
  List<ServiceRunEntity> findByConnectionIdOrderByCreatedAtDesc(UUID connectionId,
      org.springframework.data.domain.Pageable page);
}
