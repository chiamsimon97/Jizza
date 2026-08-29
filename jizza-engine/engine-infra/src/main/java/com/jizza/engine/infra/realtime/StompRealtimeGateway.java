package com.jizza.engine.infra.realtime;

import com.jizza.engine.api.realtime.RealtimeGateway;
import com.jizza.engine.api.util.Json;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * Pushes to the browser the moment something happens (R43).
 *
 * <p>Destinations are namespaced by user id even though the deployment is single-user, so the
 * multi-user path stays additive rather than needing this rewritten.
 */
@Component
public class StompRealtimeGateway implements RealtimeGateway {

  private static final Logger log = LoggerFactory.getLogger(StompRealtimeGateway.class);

  private final SimpMessagingTemplate messaging;

  public StompRealtimeGateway(SimpMessagingTemplate messaging) {
    this.messaging = messaging;
  }

  @Override
  public void publish(UUID userId, String topic, Object payload) {
    try {
      messaging.convertAndSend("/topic/" + userId + "/" + topic, payload);
    } catch (RuntimeException e) {
      // Realtime is a convenience layer; a disconnected client must never fail the work behind it.
      log.debug("Could not publish to {}/{}", userId, topic, e);
    }
  }

  @Override
  public void publishChatChunk(UUID userId, UUID conversationId, String chunk, boolean last) {
    publish(userId, "chat", Json.obj()
        .put("conversationId", conversationId.toString())
        .put("chunk", chunk)
        .put("last", last));
  }
}
