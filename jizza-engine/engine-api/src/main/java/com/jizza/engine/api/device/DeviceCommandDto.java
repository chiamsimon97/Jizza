package com.jizza.engine.api.device;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A command sent, or waiting to be sent, to a device.
 *
 * @param errorCode one of the protocol's fixed codes when it did not succeed
 * @param progress  the last progress line the device reported
 * @param files     ids of files the device uploaded with its result
 */
public record DeviceCommandDto(UUID id, UUID deviceId, String deviceName, UUID userId,
                               UUID runId, String nodeId, String action, int actionVersion,
                               JsonNode input, DeviceCommandStatus status, JsonNode output,
                               String errorCode, String error, String progress, List<UUID> files,
                               Instant deadlineAt, Instant createdAt, Instant dispatchedAt,
                               Instant acceptedAt, Instant finishedAt) {

  /** Words for the person reading a run or a chat reply. */
  public String explain() {
    return switch (status) {
      case SUCCEEDED -> "Done on " + deviceName;
      case FAILED -> deviceName + " could not do it" + (error == null ? "" : ": " + error);
      case REJECTED -> deviceName + " refused it" + (error == null ? "" : ": " + error);
      case EXPIRED -> "device_offline".equals(errorCode)
          ? deviceName + " was not reachable before the time limit"
          : deviceName + " did not finish before the time limit";
      case CANCELLED -> "Cancelled" + (error == null ? "" : ": " + error);
      default -> "Waiting for " + deviceName;
    };
  }
}
