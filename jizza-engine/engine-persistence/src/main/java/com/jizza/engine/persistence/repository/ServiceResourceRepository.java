package com.jizza.engine.persistence.repository;

import com.jizza.engine.persistence.entity.ServiceResourceEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ServiceResourceRepository extends JpaRepository<ServiceResourceEntity, UUID> {
  List<ServiceResourceEntity> findByConnectionIdOrderByLabelAsc(UUID connectionId);
}
