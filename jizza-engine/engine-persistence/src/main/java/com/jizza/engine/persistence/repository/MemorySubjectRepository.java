package com.jizza.engine.persistence.repository;

import com.jizza.engine.persistence.entity.MemorySubjectEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemorySubjectRepository extends JpaRepository<MemorySubjectEntity, UUID> {

  List<MemorySubjectEntity> findByUserIdOrderByNameAsc(UUID userId);

  Optional<MemorySubjectEntity> findByIdAndUserId(UUID id, UUID userId);

  List<MemorySubjectEntity> findByUserIdAndIdIn(UUID userId, java.util.Collection<UUID> ids);
}
