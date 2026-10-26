package com.jizza.engine.persistence.repository;

import com.jizza.engine.persistence.entity.ScriptFunctionEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScriptFunctionRepository extends JpaRepository<ScriptFunctionEntity, UUID> {
  List<ScriptFunctionEntity> findByUserIdOrderByNameAsc(UUID userId);
  Optional<ScriptFunctionEntity> findByUserIdAndName(UUID userId, String name);
  boolean existsByUserIdAndName(UUID userId, String name);
  long countByUserId(UUID userId);

  /** Jizza's public functions, which have no user. */
  List<ScriptFunctionEntity> findByUserIdIsNullOrderByNameAsc();
  Optional<ScriptFunctionEntity> findByUserIdIsNullAndName(String name);
  boolean existsByUserIdIsNullAndName(String name);
}
