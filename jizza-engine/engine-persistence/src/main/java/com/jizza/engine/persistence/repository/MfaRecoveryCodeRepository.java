package com.jizza.engine.persistence.repository;

import com.jizza.engine.persistence.entity.MfaRecoveryCodeEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MfaRecoveryCodeRepository extends JpaRepository<MfaRecoveryCodeEntity, UUID> {

  Optional<MfaRecoveryCodeEntity> findByUserIdAndCodeHashAndUsedAtIsNull(UUID userId, String codeHash);

  long countByUserIdAndUsedAtIsNull(UUID userId);

  @Modifying
  @Query("delete from MfaRecoveryCodeEntity c where c.userId = :userId")
  int deleteAllForUser(@Param("userId") UUID userId);
}
