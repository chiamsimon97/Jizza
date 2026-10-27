package com.jizza.engine.persistence.repository;

import com.jizza.engine.persistence.entity.DevicePairingEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DevicePairingRepository extends JpaRepository<DevicePairingEntity, UUID> {
  Optional<DevicePairingEntity> findByCodeHash(String codeHash);
}
