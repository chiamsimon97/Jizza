package com.jizza.common.connector;

import java.util.UUID;

/**
 * Exactly-once marker for a side-effecting connector call (R31). A {@code null} key means the call
 * is a read and needs no de-duplication.
 */
public record IdempotencyKey(UUID userId, String key) {

  public static IdempotencyKey of(UUID userId, String key) {
    return key == null ? null : new IdempotencyKey(userId, key);
  }
}
