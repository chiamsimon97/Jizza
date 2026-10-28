package com.jizza.engine.api.device;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The command broker: picks a device, keeps every command as a row before it is sent, signs it,
 * delivers it when the device is reachable, and expires it at its deadline.
 *
 * <p>Policy-free like {@code McpServers}: the guardrails are the caller's job. What it does enforce
 * is what the device itself would refuse (undeclared actions, shell off, the owner's blocked
 * actions, the device's own allowed sites and folders) so the refusal comes early and clearly.
 */
public interface DeviceCommands {

  String SAFETY_CLEANUP_NODE = "__safety_cleanup__";

  /**
   * Validates, routes and saves a command, and sends it now when the device is connected.
   *
   * @throws com.jizza.engine.api.error.ValidationException when no device of the person can run it,
   *         the inputs do not fit the action, or the device's limits would refuse it
   */
  DeviceCommandDto submit(DeviceCommandRequest request);

  /**
   * Queues a narrowly scoped safety cleanup even while autonomy is paused. Callers must use this
   * only to close resources that an already-authorized command opened.
   */
  DeviceCommandDto submitSafetyCleanup(DeviceCommandRequest request);

  Optional<DeviceCommandDto> find(UUID commandId);

  /** Stops a command; one already on the device is sent a cancel. No-op when it has finished. */
  void cancel(UUID commandId, String reason);

  /** Stops every unfinished command a run made. */
  void cancelForRun(UUID runId, String reason);

  List<DeviceCommandDto> recent(UUID deviceId, int limit);

  /** Waits up to {@code wait} for the command to finish, for a chat turn; returns it either way. */
  DeviceCommandDto await(UUID commandId, Duration wait);
}
