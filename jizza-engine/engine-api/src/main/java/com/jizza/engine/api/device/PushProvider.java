package com.jizza.engine.api.device;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A push service that wakes a phone: Apple's APNs or Google's FCM. A push only ever says "you
 * have something" and carries ids; the app fetches the rest over HTTPS after it wakes.
 */
public interface PushProvider {

  /** {@code apns} or {@code fcm}, as devices name it when they register a token. */
  String name();

  /** @return false when the token is no longer valid and should be forgotten */
  boolean send(String token, JsonNode data);
}
