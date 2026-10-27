package com.jizza.engine.api.device;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A paired device as the app shows it.
 *
 * @param ownerUserId   the person it belongs to; null for a workspace device enrolled by token
 * @param keyStorage    where its private key lives (tpm, keychain, secure_enclave, keystore, file)
 * @param online        connected now, by socket or a recent poll
 * @param state         what its last heartbeat said: idle, busy, paused
 * @param capabilities  the capabilities its manifest declared, with the user's local limits
 * @param actions       the actions it declared, as {@code name@version}
 * @param shellEnabled  whether shell commands may be sent to it at all
 * @param blockedActions actions the owner turned off for this device
 * @param tools         the {@code x-} tools it described itself, with their input and output schemas
 * @param items         its saved items by kind, such as {"recordings": [{"id","name"}]}, that
 *                      catalog inputs marked {@code x-items} take
 */
public record DeviceDto(UUID id, UUID workspaceId, UUID ownerUserId, String name,
                        DevicePlatform platform, String osVersion, String appVersion,
                        Integer protocolVersion, DeviceStatus status, List<String> tags,
                        String keyStorage, boolean online, String state, JsonNode capabilities,
                        List<String> actions, boolean shellEnabled, List<String> blockedActions,
                        Instant lastSeenAt, Instant createdAt, List<DeviceActionSpec> tools,
                        JsonNode items) {

  /** Whether it declared {@code action} in any version. */
  public boolean declares(String action) {
    if (actions == null) return false;
    return actions.stream().anyMatch(a -> a.equals(action) || a.startsWith(action + "@"));
  }

  /** The version of {@code action} it declared, or 0 when it did not. */
  public int versionOf(String action) {
    if (actions == null) return 0;
    for (String a : actions) {
      if (a.equals(action)) return 1;
      if (a.startsWith(action + "@")) {
        try {
          return Integer.parseInt(a.substring(action.length() + 1));
        } catch (NumberFormatException e) {
          return 0;
        }
      }
    }
    return 0;
  }

  public boolean weakKeyStorage() {
    return keyStorage == null || "file".equals(keyStorage);
  }
}
