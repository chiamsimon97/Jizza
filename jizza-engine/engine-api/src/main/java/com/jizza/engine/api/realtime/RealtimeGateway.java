package com.jizza.engine.api.realtime;

import java.util.UUID;

/** Pushes streamed replies and notifications to the browser the moment they occur (R43). */
public interface RealtimeGateway {

  void publish(UUID userId, String topic, Object payload);

  /** Streamed assistant token/segment for an in-flight chat turn. */
  void publishChatChunk(UUID userId, UUID conversationId, String chunk, boolean last);
}
