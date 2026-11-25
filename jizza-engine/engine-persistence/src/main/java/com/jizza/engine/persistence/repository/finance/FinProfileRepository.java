package com.jizza.engine.persistence.repository.finance;

import com.jizza.engine.persistence.entity.finance.FinProfileEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FinProfileRepository extends JpaRepository<FinProfileEntity, UUID> {

  Optional<FinProfileEntity> findByUserId(UUID userId);
}
