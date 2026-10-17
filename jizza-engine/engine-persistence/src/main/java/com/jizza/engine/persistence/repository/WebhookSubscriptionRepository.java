package com.jizza.engine.persistence.repository;

import com.jizza.engine.persistence.entity.WebhookSubscriptionEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WebhookSubscriptionRepository extends JpaRepository<WebhookSubscriptionEntity, UUID> {
  Optional<WebhookSubscriptionEntity> findByEndpointToken(String endpointToken);
  List<WebhookSubscriptionEntity> findByUserId(UUID userId);
}
