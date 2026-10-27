package com.jizza.platform.api.rest;

import com.jizza.engine.api.authz.Permissions;
import com.jizza.engine.api.device.DeviceActionSpec;
import com.jizza.engine.api.device.DeviceCommandDto;
import com.jizza.engine.api.device.DeviceDto;
import com.jizza.engine.api.device.Devices;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.util.RequiresPermission;
import com.jizza.engine.api.util.StepUpRequired;
import com.jizza.engine.security.session.CurrentWorkspace;
import com.jizza.platform.api.rest.dto.Responses;
import com.jizza.platform.usecase.device.DeviceUseCase;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Settings › Devices: pair a computer or phone running the Jizza Agent app, then name it, tag it,
 * turn shell actions on or off, test it, read its commands and logs, or remove it.
 *
 * <p>Pairing and enrollment tokens put a new device on the account, and turning shell on widens
 * what one can do, so those routes are step-up guarded. The device apps themselves talk to
 * {@link DeviceAgentController}.
 */
@RestController
@RequestMapping("/api/devices")
public class DeviceController {

  public record Changes(String name, List<String> tags, Boolean shellEnabled,
                        List<String> blockedActions) {}

  public record TokenInput(String name, Integer uses, Integer validDays, List<String> tags) {}

  private final DeviceUseCase devices;
  private final String publicUrl;

  public DeviceController(DeviceUseCase devices,
                          @Value("${jizza.devices.public-url:}") String publicUrl) {
    this.devices = devices;
    this.publicUrl = publicUrl;
  }

  @RequiresPermission(Permissions.DEVICE_VIEW)
  @GetMapping
  public List<DeviceDto> list() {
    return devices.list(CurrentWorkspace.access());
  }

  /** Every action device apps build to, for the builder's Device action step. */
  @RequiresPermission(Permissions.DEVICE_VIEW)
  @GetMapping("/catalog")
  public List<DeviceActionSpec> catalog() {
    return devices.catalog();
  }

  @RequiresPermission(Permissions.DEVICE_VIEW)
  @GetMapping("/{id}")
  public DeviceDto get(@PathVariable UUID id) {
    return devices.get(CurrentWorkspace.access(), id);
  }

  /** A six-digit code and a QR code for the app, valid for ten minutes and used once. */
  @RequiresPermission(Permissions.DEVICE_PAIR)
  @PostMapping("/pairings")
  @StepUpRequired
  public Devices.PairingCode pair() {
    return devices.startPairing(CurrentWorkspace.access(), serverUrl());
  }

  @RequiresPermission(Permissions.DEVICE_VIEW)
  @PatchMapping("/{id}")
  public DeviceDto update(@PathVariable UUID id, @RequestBody Changes changes) {
    if (Boolean.TRUE.equals(changes.shellEnabled())) {
      // Shell widens what the device can be told to do, so it goes through the step-up route.
      throw new ValidationException("Turn shell actions on with the device's Shell switch");
    }
    return devices.update(CurrentWorkspace.access(), id,
        new Devices.DeviceChanges(changes.name(), changes.tags(), changes.shellEnabled(),
            changes.blockedActions()));
  }

  @RequiresPermission(Permissions.DEVICE_VIEW)
  @PostMapping("/{id}/shell")
  @StepUpRequired
  public DeviceDto enableShell(@PathVariable UUID id) {
    return devices.update(CurrentWorkspace.access(), id,
        new Devices.DeviceChanges(null, null, true, null));
  }

  @RequiresPermission(Permissions.DEVICE_VIEW)
  @PostMapping("/{id}/disable")
  public DeviceDto disable(@PathVariable UUID id) {
    return devices.setEnabled(CurrentWorkspace.access(), id, false);
  }

  @RequiresPermission(Permissions.DEVICE_VIEW)
  @PostMapping("/{id}/enable")
  public DeviceDto enable(@PathVariable UUID id) {
    return devices.setEnabled(CurrentWorkspace.access(), id, true);
  }

  @RequiresPermission(Permissions.DEVICE_VIEW)
  @DeleteMapping("/{id}")
  public Responses.Message revoke(@PathVariable UUID id) {
    devices.revoke(CurrentWorkspace.access(), id);
    return new Responses.Message("Device removed. It can no longer sign in.");
  }

  @RequiresPermission(Permissions.DEVICE_USE)
  @PostMapping("/{id}/test")
  public DeviceCommandDto test(@PathVariable UUID id) {
    return devices.testConnection(CurrentWorkspace.access(), id);
  }

  @RequiresPermission(Permissions.DEVICE_VIEW)
  @GetMapping("/{id}/commands")
  public List<DeviceCommandDto> commands(@PathVariable UUID id,
                                         @RequestParam(defaultValue = "50") int limit) {
    return devices.commands(CurrentWorkspace.access(), id, limit);
  }

  @RequiresPermission(Permissions.DEVICE_USE)
  @PostMapping("/{id}/commands/{commandId}/cancel")
  public DeviceCommandDto cancel(@PathVariable UUID id, @PathVariable UUID commandId) {
    return devices.cancelCommand(CurrentWorkspace.access(), id, commandId);
  }

  @RequiresPermission(Permissions.DEVICE_VIEW)
  @GetMapping("/{id}/logs")
  public List<Devices.DeviceLogLine> logs(@PathVariable UUID id,
                                         @RequestParam(defaultValue = "200") int limit) {
    return devices.logs(CurrentWorkspace.access(), id, limit);
  }

  /** A file the device sent back with a result, such as a screenshot or a download. */
  @RequiresPermission(Permissions.DEVICE_VIEW)
  @GetMapping("/{id}/files/{fileId}")
  public ResponseEntity<byte[]> file(@PathVariable UUID id, @PathVariable UUID fileId) {
    Devices.DeviceFile file = devices.file(CurrentWorkspace.access(), id, fileId);
    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_OCTET_STREAM)
        .header(HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment().filename(file.name()).build().toString())
        .header("X-Content-Type-Options", "nosniff")
        .body(file.content());
  }

  @RequiresPermission(Permissions.DEVICE_MANAGE)
  @GetMapping("/enrollment-tokens")
  public List<Devices.EnrollmentToken> enrollmentTokens() {
    return devices.enrollmentTokens(CurrentWorkspace.access());
  }

  /** The token is shown once, in this answer. */
  @RequiresPermission(Permissions.DEVICE_MANAGE)
  @PostMapping("/enrollment-tokens")
  @StepUpRequired
  public Devices.EnrollmentToken createEnrollmentToken(@RequestBody TokenInput input) {
    return devices.createEnrollmentToken(CurrentWorkspace.access(), input.name(), input.uses(),
        input.validDays(), input.tags());
  }

  @RequiresPermission(Permissions.DEVICE_MANAGE)
  @DeleteMapping("/enrollment-tokens/{tokenId}")
  public Responses.Message revokeEnrollmentToken(@PathVariable UUID tokenId) {
    devices.revokeEnrollmentToken(CurrentWorkspace.access(), tokenId);
    return new Responses.Message("Enrollment token revoked.");
  }

  /** Where the app should connect: the configured public URL, or the address this request used. */
  private String serverUrl() {
    if (publicUrl != null && !publicUrl.isBlank()) return publicUrl.replaceAll("/+$", "");
    return ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
  }
}
