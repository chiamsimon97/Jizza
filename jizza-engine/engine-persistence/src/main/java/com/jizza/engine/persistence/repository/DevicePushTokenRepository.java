package com.jizza.engine.persistence.repository;

import com.jizza.engine.persistence.entity.DevicePushTokenEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DevicePushTokenRepository extends JpaRepository<DevicePushTokenEntity, UUID> {}
