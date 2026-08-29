package com.jizza.engine.persistence.repository;

import com.jizza.engine.persistence.entity.InboundEventEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InboundEventRepository extends JpaRepository<InboundEventEntity, UUID> {
  List<InboundEventEntity> findBySubscriptionIdOrderByReceivedAtDesc(UUID subscriptionId);
}
