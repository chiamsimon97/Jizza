package com.jizza.engine.persistence.repository;

import com.jizza.engine.persistence.entity.SkillEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SkillRepository extends JpaRepository<SkillEntity, UUID> {
  List<SkillEntity> findByUserId(UUID userId);
  List<SkillEntity> findByWorkflowId(UUID workflowId);
  Optional<SkillEntity> findByWorkflowIdAndVersion(UUID workflowId, int version);
  Optional<SkillEntity> findByUserIdAndName(UUID userId, String name);
}
