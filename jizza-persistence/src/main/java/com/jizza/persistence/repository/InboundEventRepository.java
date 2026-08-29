package com.jizza.persistence.repository;

import com.jizza.persistence.entity.InboundEventEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InboundEventRepository extends JpaRepository<InboundEventEntity, UUID> {
  List<InboundEventEntity> findBySubscriptionIdOrderByReceivedAtDesc(UUID subscriptionId);
}
