package com.jizza.persistence.repository;

import com.jizza.persistence.entity.OAuthStateEntity;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OAuthStateRepository extends JpaRepository<OAuthStateEntity, UUID> {

  Optional<OAuthStateEntity> findByState(String state);

  @Modifying
  @Query("delete from OAuthStateEntity s where s.expiresAt < :cutoff")
  int deleteExpired(@Param("cutoff") Instant cutoff);
}
