package com.jizza.engine.connectors.tools.service;

import com.jizza.engine.api.service.ServiceModel;
import com.jizza.engine.api.service.ServiceRunner;
import com.jizza.engine.persistence.entity.ServiceTriggerEntity;
import com.jizza.engine.persistence.repository.ConnectionRepository;
import com.jizza.engine.persistence.repository.ServiceTriggerRepository;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Polls the enabled triggers of live services when their interval comes round. One failing
 * service does not hold up the rest: each poll's failure is recorded in that service's history.
 */
@Component
public class TriggerPoller {

  private static final Logger log = LoggerFactory.getLogger(TriggerPoller.class);

  private final ServiceTriggerRepository triggers;
  private final ConnectionRepository services;
  private final ServiceRunner runner;
  private final boolean enabled;

  public TriggerPoller(ServiceTriggerRepository triggers, ConnectionRepository services,
                       ServiceRunner runner,
                       @Value("${jizza.connectors.triggers.enabled:true}") boolean enabled) {
    this.triggers = triggers;
    this.services = services;
    this.runner = runner;
    this.enabled = enabled;
  }

  @Scheduled(fixedDelayString = "${jizza.connectors.triggers.check-interval:PT1M}")
  public void pollDue() {
    if (!enabled) return;
    Instant now = Instant.now();
    for (ServiceTriggerEntity trigger : triggers.findByEnabledTrue()) {
      if (!"polling".equals(trigger.getKind()) || !isDue(trigger, now)) continue;
      boolean live = services.findById(trigger.getConnectionId())
          .map(s -> ServiceModel.LIVE.equals(s.getVersionStatus())).orElse(false);
      if (!live) continue;
      try {
        runner.pollTrigger(trigger.getConnectionId(), trigger.getId());
      } catch (RuntimeException e) {
        log.warn("Polling trigger {} failed", trigger.getId(), e);
      }
    }
  }

  static boolean isDue(ServiceTriggerEntity trigger, Instant now) {
    return trigger.getLastPolledAt() == null || !trigger.getLastPolledAt()
        .plus(Duration.ofMinutes(trigger.getPollIntervalMinutes())).isAfter(now);
  }
}
