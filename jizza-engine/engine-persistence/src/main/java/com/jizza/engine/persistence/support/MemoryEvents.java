package com.jizza.engine.persistence.support;

import com.jizza.engine.api.realtime.RealtimeGateway;
import com.jizza.engine.api.util.Json;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Tells an open Memory Cosmos that something changed, so it refetches instead of reloading. The
 * payload is ids only: memory content never travels over the socket. Sent after the transaction
 * commits, so the client never refetches a change that then rolls back.
 */
@Component
public class MemoryEvents {

  public static final String TOPIC = "memory";

  public static final String CREATED = "MEMORY_CREATED";
  public static final String UPDATED = "MEMORY_UPDATED";
  public static final String FORGOTTEN = "MEMORY_FORGOTTEN";
  public static final String VISIBILITY = "VISIBILITY_CHANGED";
  public static final String BODY = "BODY_CHANGED";

  private final ObjectProvider<RealtimeGateway> gateway;

  public MemoryEvents(ObjectProvider<RealtimeGateway> gateway) {
    this.gateway = gateway;
  }

  /** @param itemKind {@code fact}, {@code note}, or null for a body (a subject) */
  public void changed(UUID userId, String kind, String itemKind, UUID itemId) {
    Runnable send = () -> gateway.ifAvailable(g -> {
      var payload = Json.obj().put("kind", kind);
      if (itemKind != null) payload.put("itemKind", itemKind);
      if (itemId != null) payload.put("itemId", itemId.toString());
      g.publish(userId, TOPIC, payload);
    });
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override public void afterCommit() { send.run(); }
      });
    } else {
      send.run();
    }
  }
}
