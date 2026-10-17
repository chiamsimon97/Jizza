package com.jizza.engine.persistence.repository;

import com.jizza.engine.persistence.entity.KillSwitchEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KillSwitchRepository extends JpaRepository<KillSwitchEntity, UUID> {}
