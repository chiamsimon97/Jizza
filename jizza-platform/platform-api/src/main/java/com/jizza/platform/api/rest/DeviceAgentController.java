package com.jizza.platform.api.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.device.DeviceActionSpec;
import com.jizza.engine.api.device.DeviceAgentGateway;
import com.jizza.engine.api.device.Devices;
import com.jizza.engine.api.error.AuthException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.util.PublicEndpoint;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The HTTPS half of agent protocol v1, for Jizza Agent apps on Windows, macOS, Linux, Android and
 * iOS. The same messages also travel over the WebSocket at {@code /agent/v1/ws}; this side is for
 * enrolling, signing in, and for a device that cannot hold a socket open (a phone in the
 * background), which long-polls for commands and posts its messages.
 *
 * <p>None of it uses a browser session: enrolling spends a one-time pairing code or an enrollment
 * token, signing in answers a challenge with the device's own key, and everything else carries the
 * short-lived device token as {@code Authorization: Bearer}. See docs/agent-protocol-v1.md.
 */
@RestController
@PublicEndpoint
@RequestMapping("/agent/v1")
public class DeviceAgentController {

  public record ChallengeInput(UUID deviceId) {}

  public record TokenInput(UUID deviceId, String nonce, String signature) {}

  public record PushTokenInput(String provider, String token) {}

  public record RotateInput(String publicKey, String signature) {}

  public record Polled(List<JsonNode> messages) {}

  private final DeviceAgentGateway gateway;
  private final long maxFileBytes;

  public DeviceAgentController(DeviceAgentGateway gateway,
                               @Value("${jizza.devices.max-file-bytes:26214400}") long maxFileBytes) {
    this.gateway = gateway;
    this.maxFileBytes = maxFileBytes;
  }

  /** Spends a pairing code or an enrollment token and registers the device's public key. */
  @PostMapping("/enroll")
  public DeviceAgentGateway.Enrolled enroll(@RequestBody DeviceAgentGateway.Enrollment enrollment,
                                            HttpServletRequest request) {
    return gateway.enroll(enrollment, request.getRemoteAddr());
  }

  @PostMapping("/auth/challenge")
  public DeviceAgentGateway.Challenge challenge(@RequestBody ChallengeInput input) {
    if (input == null || input.deviceId() == null) throw new ValidationException("deviceId is required");
    return gateway.challenge(input.deviceId());
  }

  /** The device signs the nonce with its key and gets a 15-minute token. */
  @PostMapping("/auth/token")
  public DeviceAgentGateway.AccessToken token(@RequestBody TokenInput input,
                                              HttpServletRequest request) {
    if (input == null || input.deviceId() == null) throw new ValidationException("deviceId is required");
    return gateway.token(input.deviceId(), input.nonce(), input.signature(), request.getRemoteAddr());
  }

  /** Waits up to {@code wait} seconds for commands and other server messages. */
  @GetMapping("/commands")
  public Polled commands(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
                         String authorization, @RequestParam(defaultValue = "0") int wait) {
    DeviceAgentGateway.Caller caller = caller(authorization);
    return new Polled(gateway.poll(caller, Duration.ofSeconds(Math.max(0, wait))));
  }

  /** One protocol message from the device; the answer is the server's ack, error or reply. */
  @PostMapping("/messages")
  public JsonNode message(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
                          String authorization, @RequestBody JsonNode envelope) {
    return gateway.receive(caller(authorization), envelope);
  }

  /** A file for a command's result, sent as the raw body. */
  @PutMapping("/files")
  public Map<String, String> upload(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
                                    String authorization,
                                    @RequestParam(required = false) UUID commandId,
                                    @RequestParam String name, HttpServletRequest request)
      throws IOException {
    DeviceAgentGateway.Caller caller = caller(authorization);
    if (request.getContentLengthLong() > maxFileBytes) throw tooLarge();
    byte[] content;
    try (InputStream in = request.getInputStream()) {
      content = in.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, maxFileBytes + 1));
    }
    if (content.length > maxFileBytes) throw tooLarge();
    UUID id = gateway.upload(caller, commandId, name, request.getContentType(), content);
    return Map.of("fileId", id.toString());
  }

  /** A file a command hands the device, such as a document to open or attach. */
  @GetMapping("/files/{fileId}")
  public ResponseEntity<byte[]> download(@RequestHeader(value = HttpHeaders.AUTHORIZATION,
      required = false) String authorization, @PathVariable UUID fileId) {
    Devices.DeviceFile file = gateway.download(caller(authorization), fileId);
    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_OCTET_STREAM)
        .header(HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment().filename(file.name()).build().toString())
        .body(file.content());
  }

  /** The phone's APNs or FCM token, so the server can wake it for work. */
  @PostMapping("/push-token")
  public Map<String, Boolean> pushToken(@RequestHeader(value = HttpHeaders.AUTHORIZATION,
      required = false) String authorization, @RequestBody PushTokenInput input) {
    gateway.registerPushToken(caller(authorization), input.provider(), input.token());
    return Map.of("ok", true);
  }

  /** Replaces the device key; the new key is signed by the current one. */
  @PostMapping("/keys/rotate")
  public Map<String, Boolean> rotate(@RequestHeader(value = HttpHeaders.AUTHORIZATION,
      required = false) String authorization, @RequestBody RotateInput input) {
    gateway.rotateKey(caller(authorization), input.publicKey(), input.signature());
    return Map.of("ok", true);
  }

  /** The action catalog, so an app can check its manifest before it sends it. */
  @GetMapping("/catalog")
  public List<DeviceActionSpec> catalog(@RequestHeader(value = HttpHeaders.AUTHORIZATION,
      required = false) String authorization) {
    caller(authorization);
    return gateway.catalog();
  }

  private DeviceAgentGateway.Caller caller(String authorization) {
    if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
      throw new AuthException("device_token", "A valid device token is required");
    }
    return gateway.authenticate(authorization.substring(7).trim());
  }

  private ValidationException tooLarge() {
    return new ValidationException("That file is larger than " + (maxFileBytes / (1024 * 1024))
        + " MB");
  }
}
