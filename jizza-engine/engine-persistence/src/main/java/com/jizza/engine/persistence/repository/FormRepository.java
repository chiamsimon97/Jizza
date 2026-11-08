package com.jizza.engine.persistence.repository;

import com.jizza.engine.persistence.entity.FormEntity;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FormRepository extends JpaRepository<FormEntity, UUID> {
  List<FormEntity> findByUserIdOrderByUpdatedAtDesc(UUID userId);
  Optional<FormEntity> findByPublicToken(String publicToken);

  /** The form, locked, so two submissions at once still get different numbers. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select f from FormEntity f where f.id = :id")
  Optional<FormEntity> lockById(@Param("id") UUID id);
}
