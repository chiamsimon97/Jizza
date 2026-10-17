package com.jizza.engine.persistence.repository;

import com.jizza.engine.persistence.entity.ServiceVariableEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ServiceVariableRepository extends JpaRepository<ServiceVariableEntity, UUID> {
  List<ServiceVariableEntity> findByConnectionIdOrderByNameAsc(UUID connectionId);
}
