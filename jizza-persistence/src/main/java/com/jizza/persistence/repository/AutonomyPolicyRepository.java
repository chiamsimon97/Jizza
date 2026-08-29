package com.jizza.persistence.repository;

import com.jizza.persistence.entity.AutonomyPolicyEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AutonomyPolicyRepository extends JpaRepository<AutonomyPolicyEntity, UUID> {
  List<AutonomyPolicyEntity> findByUserIdOrderByPrecedenceDesc(UUID userId);
  void deleteByUserId(UUID userId);
}
