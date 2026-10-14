package com.jizza.engine.persistence.repository;

import com.jizza.engine.persistence.entity.MemoryAccessEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MemoryAccessRepository extends JpaRepository<MemoryAccessEntity, Long> {

  List<MemoryAccessEntity> findByItemKindAndItemIdOrderByOccurredAtDesc(String itemKind, UUID itemId,
                                                                        Pageable page);

  long countByItemKindAndItemId(String itemKind, UUID itemId);

  List<MemoryAccessEntity> findByUserIdOrderByOccurredAtAsc(UUID userId);

  @Modifying
  @Query("delete from MemoryAccessEntity a where a.itemKind = :kind and a.itemId = :id")
  int deleteItem(@Param("kind") String itemKind, @Param("id") UUID itemId);
}
