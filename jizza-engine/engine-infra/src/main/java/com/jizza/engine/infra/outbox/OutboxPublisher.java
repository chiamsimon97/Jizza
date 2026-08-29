package com.jizza.engine.infra.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.realtime.RealtimeGateway;
import com.jizza.engine.persistence.entity.OutboxEntity;
import com.jizza.engine.persistence.repository.OutboxRepository;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactional outbox (R50).
 *
 * <p>A side effect written in the same transaction as the state change it describes cannot be lost
 * or emitted for a change that rolled back. This publisher drains the table afterwards, at
 * least-once: consumers are expected to tolerate a repeat, which is cheaper than trying to make
 * delivery exactly-once across a process boundary.
 */
@Component
public class OutboxPublisher {

  private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
  private static final int BATCH = 50;

  private final OutboxRepository outbox;
  private final RealtimeGateway realtime;

  public OutboxPublisher(OutboxRepository outbox, RealtimeGateway realtime) {
    this.outbox = outbox;
    this.realtime = realtime;
  }

  /** Records an event in the caller's transaction. */
  @Transactional
  public UUID record(UUID userId, String aggregate, UUID aggregateId, String eventType,
                     JsonNode payload) {
    OutboxEntity entity = new OutboxEntity();
    entity.setUserId(userId);
    entity.setAggregate(aggregate);
    entity.setAggregateId(aggregateId);
    entity.setEventType(eventType);
    entity.setPayload(payload);
    entity.setPublished(false);
    return outbox.save(entity).getId();
  }

  @Scheduled(fixedDelayString = "${jizza.platform.outbox.poll-interval:PT2S}")
  @Transactional
  public void drain() {
    for (OutboxEntity entry : outbox.findByPublishedFalseOrderByCreatedAtAsc(PageRequest.of(0, BATCH))) {
      try {
        realtime.publish(entry.getUserId(), entry.getAggregate(), entry.getPayload());
        entry.setPublished(true);
        entry.setPublishedAt(Instant.now());
        outbox.save(entry);
      } catch (RuntimeException e) {
        // Leave it unpublished; the next pass tries again.
        log.warn("Could not publish outbox entry {}", entry.getId(), e);
      }
    }
  }
}
