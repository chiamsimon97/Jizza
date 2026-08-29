package com.jizza.engine.api.notification;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.UUID;

/**
 * Everything the bus needs to raise an item. Named {@code Ctx} in the Architecture Contract.
 *
 * @param expiresIn null lets the bus apply the configured default for the kind
 */
public record NotificationContext(UUID userId, String body, JsonNode detail,
                                  UUID taskId, UUID workflowId, UUID runId,
                                  Duration expiresIn) {

  public static NotificationContext info(UUID userId, String body) {
    return new NotificationContext(userId, body, null, null, null, null, null);
  }

  public static NotificationContext forRun(UUID userId, String body, JsonNode detail, UUID runId) {
    return new NotificationContext(userId, body, detail, null, null, runId, null);
  }

  public static NotificationContext forTask(UUID userId, String body, JsonNode detail, UUID taskId) {
    return new NotificationContext(userId, body, detail, taskId, null, null, null);
  }

  public static NotificationContext forWorkflow(UUID userId, String body, JsonNode detail,
                                                UUID workflowId, UUID taskId) {
    return new NotificationContext(userId, body, detail, taskId, workflowId, null, null);
  }
}
