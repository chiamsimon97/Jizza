package com.jizza.persistence.repository;

import com.jizza.persistence.entity.GeneratedToolEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GeneratedToolRepository extends JpaRepository<GeneratedToolEntity, UUID> {

  List<GeneratedToolEntity> findByConnectionId(UUID connectionId);

  Optional<GeneratedToolEntity> findByConnectionIdAndName(UUID connectionId, String name);

  void deleteByConnectionId(UUID connectionId);

  @Query("""
      select t from GeneratedToolEntity t
      where t.enabled = true and t.connectionId in (
        select c.id from ConnectionEntity c where c.userId = :userId)
      """)
  List<GeneratedToolEntity> findEnabledForUser(@Param("userId") UUID userId);
}
