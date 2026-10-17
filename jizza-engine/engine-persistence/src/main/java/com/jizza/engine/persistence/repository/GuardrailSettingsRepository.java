package com.jizza.engine.persistence.repository;

import com.jizza.engine.persistence.entity.GuardrailSettingsEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GuardrailSettingsRepository extends JpaRepository<GuardrailSettingsEntity, UUID> {}
