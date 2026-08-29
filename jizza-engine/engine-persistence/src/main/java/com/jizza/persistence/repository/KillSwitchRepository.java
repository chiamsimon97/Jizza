package com.jizza.persistence.repository;

import com.jizza.persistence.entity.KillSwitchEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KillSwitchRepository extends JpaRepository<KillSwitchEntity, UUID> {}
