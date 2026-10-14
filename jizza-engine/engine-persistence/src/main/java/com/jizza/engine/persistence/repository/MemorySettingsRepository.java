package com.jizza.engine.persistence.repository;

import com.jizza.engine.persistence.entity.MemorySettingsEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemorySettingsRepository extends JpaRepository<MemorySettingsEntity, UUID> {

  List<MemorySettingsEntity> findByRetentionDaysNotNull();
}
