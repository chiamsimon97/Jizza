package com.jizza.engine.persistence.repository;

import com.jizza.engine.persistence.entity.MemorySubjectRelationEntity;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemorySubjectRelationRepository
    extends JpaRepository<MemorySubjectRelationEntity, UUID> {

  List<MemorySubjectRelationEntity> findByUserId(UUID userId);

  List<MemorySubjectRelationEntity> findByFromSubjectIdIn(Collection<UUID> subjectIds);

  List<MemorySubjectRelationEntity> findByToSubjectIdIn(Collection<UUID> subjectIds);

  Optional<MemorySubjectRelationEntity> findByFromSubjectIdAndToSubjectIdAndRelation(
      UUID fromSubjectId, UUID toSubjectId, String relation);

  Optional<MemorySubjectRelationEntity> findByIdAndUserId(UUID id, UUID userId);
}
