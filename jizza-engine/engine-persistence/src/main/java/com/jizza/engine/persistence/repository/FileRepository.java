package com.jizza.engine.persistence.repository;

import com.jizza.engine.persistence.entity.FileEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FileRepository extends JpaRepository<FileEntity, UUID> {
  List<FileEntity> findByUserId(UUID userId);

  List<FileEntity> findByAgentIdOrderByCreatedAtDesc(UUID agentId);
}
