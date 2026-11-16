package com.jizza.engine.api.device;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Paired devices as the web app manages them: pairing codes, enrollment tokens, renaming, tags,
 * limits, disabling and revoking. Who may do what is the platform's business; every call here is
 * already authorised.
 */
public interface Devices {

  /**
   * A one-time pairing code for a person's own device.
   *
   * @param qrPayload what the QR code encodes: the server address and the code. It is also the
   *                  installation key a person copies into a desktop app
   * @param id        for asking whether a device has used it yet
   */
  record PairingCode(String code, Instant expiresAt, String qrPayload, UUID id) {}

  /**
   * Whether a pairing code has been used.
   *
   * @param device the device that used it; null until one has (or when it was since revoked)
   */
  record PairingStatus(UUID id, Instant expiresAt, boolean used, boolean expired, DeviceDto device) {}

  /**
   * @param token shown once, when it is created; null when listing
   */
  record EnrollmentToken(UUID id, String name, String token, int usesLeft, Instant expiresAt,
                         List<String> tags, UUID createdBy, Instant createdAt, boolean revoked) {}

  /**
   * Null fields are left as they are.
   *
   * @param toolTypes what each tool the device added itself does, for the guardrails; a tool left
   *                  out keeps its type, and a tool never set is judged as moving money
   */
  record DeviceChanges(String name, List<String> tags, Boolean shellEnabled,
                       List<String> blockedActions,
                       java.util.Map<String, com.jizza.engine.api.enums.ActionType> toolTypes,
                       Boolean autoUpdate) {}

  /** The person's own devices and the workspace's shared ones. */
  List<DeviceDto> list(UUID workspaceId, UUID userId);

  /**
   * The person's own active devices in every account, the pool a workflow run of theirs draws
   * from, so they can choose which browser runs it.
   */
  default List<DeviceDto> owned(UUID userId) {
    return List.of();
  }

  /** Every device of the account that is not revoked, for someone who manages them. */
  List<DeviceDto> listAll(UUID workspaceId);

  Optional<DeviceDto> find(UUID deviceId);

  PairingCode startPairing(UUID userId, UUID workspaceId, String serverUrl);

  /**
   * Download links for the agent app by platform and architecture, such as {@code macos-arm64};
   * a platform with no link has no published installer yet.
   */
  Map<String, String> installers();

  /** Whether a code this person started has been used; empty when it is not theirs. */
  Optional<PairingStatus> pairingStatus(UUID userId, UUID pairingId);

  EnrollmentToken createEnrollmentToken(UUID userId, UUID workspaceId, String name, int uses,
                                        Duration validFor, List<String> tags);

  List<EnrollmentToken> enrollmentTokens(UUID workspaceId);

  void revokeEnrollmentToken(UUID workspaceId, UUID tokenId);

  DeviceDto update(UUID deviceId, DeviceChanges changes);

  DeviceDto setEnabled(UUID deviceId, boolean enabled);

  /** Final: its keys stop working, its socket is closed and its queued commands are cancelled. */
  DeviceDto revoke(UUID deviceId, String reason);

  /** Revokes a person's own devices in a workspace they no longer belong to. */
  int revokeOwnedBy(UUID userId, UUID workspaceId, String reason);

  /** Sends {@code system.ping} and returns the command, to test the link. */
  DeviceCommandDto ping(UUID userId, UUID workspaceId, UUID deviceId);

  /** The device's newest diagnostic lines, kept for a week. */
  List<DeviceLogLine> logs(UUID deviceId, int limit);

  record DeviceLogLine(Instant ts, String level, String message, UUID commandId) {}

  /** A file a device uploaded, for the person to download. */
  Optional<DeviceFile> file(UUID fileId);

  /**
   * Stores a file the server hands {@code deviceId} (it downloads it with {@code GET
   * /agent/v1/files/{id}}), such as the steps of a recording it is to edit. Encrypted at rest.
   */
  default UUID handFile(UUID deviceId, String name, String contentType, byte[] content) {
    throw new UnsupportedOperationException("Files for devices are not supported here");
  }

  record DeviceFile(UUID id, UUID deviceId, UUID commandId, String name, String contentType,
                    long size, byte[] content) {}

  /** What the Jizza app on this person's phone may do with their account. */
  default PhoneAccess phoneAccess(UUID deviceId) {
    return PhoneAccess.APPROVE;
  }

  default PhoneAccess setPhoneAccess(UUID deviceId, PhoneAccess access) {
    throw new UnsupportedOperationException("Phone access is not stored here");
  }
}
