package com.jizza.engine.persistence.repository;

import com.jizza.engine.persistence.entity.PlanEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlanRepository extends JpaRepository<PlanEntity, UUID> {

  List<PlanEntity> findByUserIdOrderByCreatedAtDesc(UUID userId);

  List<PlanEntity> findByUserIdAndTitleIgnoreCase(UUID userId, String title);
}
