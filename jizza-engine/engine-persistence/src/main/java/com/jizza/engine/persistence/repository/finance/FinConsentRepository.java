package com.jizza.engine.persistence.repository.finance;

import com.jizza.engine.persistence.entity.finance.FinConsentEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FinConsentRepository extends JpaRepository<FinConsentEntity, UUID> {

  List<FinConsentEntity> findByUserIdAndPersonIdOrderByGrantedAtDesc(UUID userId, UUID personId);

  List<FinConsentEntity> findByUserIdOrderByGrantedAtDesc(UUID userId);

  Optional<FinConsentEntity> findByIdAndUserId(UUID id, UUID userId);
}
