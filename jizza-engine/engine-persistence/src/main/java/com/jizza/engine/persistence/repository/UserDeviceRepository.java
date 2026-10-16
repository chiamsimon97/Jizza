package com.jizza.engine.persistence.repository;

import com.jizza.engine.persistence.entity.UserDeviceEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserDeviceRepository extends JpaRepository<UserDeviceEntity, UUID> {

  Optional<UserDeviceEntity> findByUserIdAndFingerprint(UUID userId, String fingerprint);

  boolean existsByUserId(UUID userId);
}
