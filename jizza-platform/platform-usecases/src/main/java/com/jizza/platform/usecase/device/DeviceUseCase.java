package com.jizza.platform.usecase.device;

import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.authz.Access;
import com.jizza.engine.api.authz.PermissionEvaluator;
import com.jizza.engine.api.authz.Permissions;
import com.jizza.engine.api.device.DeviceActionCatalog;
import com.jizza.engine.api.device.DeviceActionSpec;
import com.jizza.engine.api.device.DeviceCommandDto;
import com.jizza.engine.api.device.DeviceCommands;
import com.jizza.engine.api.device.DeviceDto;
import com.jizza.engine.api.device.Devices;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.util.Json;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Settings › Devices: pairing a device, seeing and changing the ones paired, enrollment tokens for
 * machines nobody sits at, and the commands and logs of each.
 *
 * <p>What is decided here: a person sees and changes their own devices, and the account's shared
 * ones; someone with {@code device_manage} sees and changes every device of the account; a device
 * of another account reads as missing.
 */
@Service
public class DeviceUseCase {

  static final int MAX_ENROLLMENT_USES = 1000;
  static final Duration MAX_ENROLLMENT_VALIDITY = Duration.ofDays(90);

  private final Devices devices;
  private final DeviceCommands commands;
  private final DeviceActionCatalog catalog;
  private final PermissionEvaluator permissions;
  private final AuditLog audit;

  public DeviceUseCase(Devices devices, DeviceCommands commands, DeviceActionCatalog catalog,
                       PermissionEvaluator permissions, AuditLog audit) {
    this.devices = devices;
    this.commands = commands;
    this.catalog = catalog;
    this.permissions = permissions;
    this.audit = audit;
  }

  public List<DeviceDto> list(Access access) {
    return manager(access) ? devices.listAll(access.workspaceId())
        : devices.list(access.workspaceId(), access.userId());
  }

  public DeviceDto get(Access access, UUID deviceId) {
    return visible(access, deviceId);
  }

  public Devices.PairingCode startPairing(Access access, String serverUrl) {
    return devices.startPairing(access.userId(), access.workspaceId(), serverUrl);
  }

  public DeviceDto update(Access access, UUID deviceId, Devices.DeviceChanges changes) {
    DeviceDto device = visible(access, deviceId);
    if (changes.name() != null && changes.name().isBlank()) {
      throw new ValidationException("Give the device a name");
    }
    DeviceDto updated = devices.update(device.id(), changes);
    audit.record(access.userId(), ActorType.USER, null, "device.updated", updated.name(),
        Json.obj().put("deviceId", device.id().toString())
            .put("shellEnabled", updated.shellEnabled()));
    return updated;
  }

  public DeviceDto setEnabled(Access access, UUID deviceId, boolean enabled) {
    DeviceDto device = visible(access, deviceId);
    DeviceDto changed = devices.setEnabled(device.id(), enabled);
    audit.record(access.userId(), ActorType.USER, null,
        enabled ? "device.enabled" : "device.disabled", device.name(),
        Json.obj().put("deviceId", device.id().toString()));
    return changed;
  }

  public void revoke(Access access, UUID deviceId) {
    DeviceDto device = visible(access, deviceId);
    devices.revoke(device.id(), "Removed in Settings");
  }

  public List<DeviceCommandDto> commands(Access access, UUID deviceId, int limit) {
    return commands.recent(visible(access, deviceId).id(), Math.max(1, Math.min(limit, 200)));
  }

  public DeviceCommandDto cancelCommand(Access access, UUID deviceId, UUID commandId) {
    DeviceDto device = visible(access, deviceId);
    DeviceCommandDto command = commands.find(commandId)
        .filter(c -> c.deviceId().equals(device.id()))
        .orElseThrow(() -> new NotFoundException("Device command", commandId));
    commands.cancel(command.id(), "Cancelled in Settings");
    return commands.find(command.id()).orElse(command);
  }

  public List<Devices.DeviceLogLine> logs(Access access, UUID deviceId, int limit) {
    return devices.logs(visible(access, deviceId).id(), Math.max(1, Math.min(limit, 500)));
  }

  /** Sends a ping and waits a few seconds, so the person sees the round trip work. */
  public DeviceCommandDto testConnection(Access access, UUID deviceId) {
    DeviceDto device = visible(access, deviceId);
    DeviceCommandDto sent = devices.ping(access.userId(), access.workspaceId(), device.id());
    return commands.await(sent.id(), Duration.ofSeconds(8));
  }

  public Devices.DeviceFile file(Access access, UUID deviceId, UUID fileId) {
    DeviceDto device = visible(access, deviceId);
    return devices.file(fileId).filter(f -> f.deviceId().equals(device.id()))
        .orElseThrow(() -> new NotFoundException("Device file", fileId));
  }

  public List<Devices.EnrollmentToken> enrollmentTokens(Access access) {
    return devices.enrollmentTokens(access.workspaceId());
  }

  public Devices.EnrollmentToken createEnrollmentToken(Access access, String name, Integer uses,
                                                       Integer validDays, List<String> tags) {
    if (name == null || name.isBlank()) throw new ValidationException("Give the token a name");
    int count = uses == null ? 1 : uses;
    if (count < 1 || count > MAX_ENROLLMENT_USES) {
      throw new ValidationException("A token can enroll between 1 and " + MAX_ENROLLMENT_USES
          + " machines");
    }
    Duration validFor = Duration.ofDays(validDays == null ? 7 : validDays);
    if (validFor.isNegative() || validFor.isZero() || validFor.compareTo(MAX_ENROLLMENT_VALIDITY) > 0) {
      throw new ValidationException("A token can be valid for 1 to 90 days");
    }
    return devices.createEnrollmentToken(access.userId(), access.workspaceId(), name.strip(), count,
        validFor, tags == null ? List.of() : tags);
  }

  public void revokeEnrollmentToken(Access access, UUID tokenId) {
    devices.revokeEnrollmentToken(access.workspaceId(), tokenId);
    audit.record(access.userId(), ActorType.USER, null, "device.enrollment_token_revoked", null,
        Json.obj().put("tokenId", tokenId.toString()));
  }

  public List<DeviceActionSpec> catalog() {
    return catalog.all();
  }

  private boolean manager(Access access) {
    return permissions.isAllowed(access.userId(), access.workspaceId(), Permissions.DEVICE_MANAGE);
  }

  private DeviceDto visible(Access access, UUID deviceId) {
    DeviceDto device = devices.find(deviceId)
        .filter(d -> d.workspaceId().equals(access.workspaceId()))
        .filter(d -> d.status() != com.jizza.engine.api.device.DeviceStatus.REVOKED)
        .orElseThrow(() -> new NotFoundException("Device", deviceId));
    boolean mine = device.ownerUserId() == null || device.ownerUserId().equals(access.userId());
    if (!mine && !manager(access)) throw new NotFoundException("Device", deviceId);
    return device;
  }
}
