package com.jizza.persistence.repository;

import com.jizza.persistence.entity.PasswordResetTokenEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetTokenEntity, UUID> {
  Optional<PasswordResetTokenEntity> findByTokenHash(String tokenHash);
}
