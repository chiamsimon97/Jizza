package com.jizza.engine.persistence.repository;

import com.jizza.engine.persistence.entity.ScriptFunctionInstallEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

public interface ScriptFunctionInstallRepository extends JpaRepository<ScriptFunctionInstallEntity, UUID> {
  List<ScriptFunctionInstallEntity> findByUserId(UUID userId);
  boolean existsByUserIdAndFunctionId(UUID userId, UUID functionId);

  @Transactional
  long deleteByUserIdAndFunctionId(UUID userId, UUID functionId);
}
