package com.jizza.engine.persistence.repository;

import com.jizza.engine.persistence.entity.DeviceChallengeEntity;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface DeviceChallengeRepository extends JpaRepository<DeviceChallengeEntity, String> {
  @Modifying
  @Query("delete from DeviceChallengeEntity c where c.expiresAt < :now")
  int deleteExpired(Instant now);
}
