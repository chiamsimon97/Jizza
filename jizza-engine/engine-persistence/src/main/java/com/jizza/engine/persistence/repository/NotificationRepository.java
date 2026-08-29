package com.jizza.engine.persistence.repository;

import com.jizza.engine.api.enums.NotificationStatus;
import com.jizza.engine.persistence.entity.NotificationEntity;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<NotificationEntity, UUID> {

  List<NotificationEntity> findByUserIdOrderByCreatedAtDesc(UUID userId);

  List<NotificationEntity> findByUserIdAndStatusOrderByCreatedAtDesc(UUID userId, NotificationStatus status);

  List<NotificationEntity> findByRunId(UUID runId);

  /** Blocking items that are still open — the input to reminders, escalation and auto-cancel. */
  @Query("""
      select n from NotificationEntity n
      where n.status in (com.jizza.engine.api.enums.NotificationStatus.UNREAD,
                         com.jizza.engine.api.enums.NotificationStatus.READ)
        and n.kind <> com.jizza.engine.api.enums.NotificationKind.INFO
        and n.expiresAt is not null and n.expiresAt <= :horizon
      """)
  List<NotificationEntity> findOpenBlockingDueBy(@Param("horizon") Instant horizon);
}
