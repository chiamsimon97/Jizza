package com.jizza.engine.api.device;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The server side of the Jizza Agent Protocol, as the HTTPS routes under {@code /agent/v1} reach
 * it. The WebSocket at {@code /agent/v1/ws} speaks the same messages through the same handler.
 *
 * <p>Everything here is reachable without a browser session: a device proves itself with the key
 * it made at enrollment, and each call after sign-in carries the short-lived token that proof
 * bought. Nothing in this interface trusts a device id it was not given by {@link #authenticate}.
 */
public interface DeviceAgentGateway {

  /**
   * @param code            a pairing code (a person's device) ...
   * @param enrollmentToken ... or an enrollment token (a workspace machine), exactly one of them
   * @param publicKey       {@code ed25519:<base64 of the X.509 key>}
   */
  record Enrollment(String code, String enrollmentToken, String publicKey, String keyStorage,
                    String name, String platform, String osVersion, String appVersion,
                    List<Integer> protocol) {}

  record Enrolled(UUID deviceId, String serverSigningKey, int protocolVersion) {}

  record Challenge(String nonce, Instant expiresAt) {}

  record AccessToken(String token, Instant expiresAt) {}

  /** The device a token belongs to, checked against the device's current status. */
  record Caller(UUID deviceId, UUID userId, UUID workspaceId) {}

  Enrolled enroll(Enrollment enrollment, String remoteAddress);

  Challenge challenge(UUID deviceId);

  /** @param signature base64 Ed25519 signature over the nonce's UTF-8 bytes */
  AccessToken token(UUID deviceId, String nonce, String signature, String remoteAddress);

  /** @throws com.jizza.engine.api.error.AuthException for a bad, expired or revoked token */
  Caller authenticate(String bearerToken);

  /**
   * One message from the device. Returns what the server answers straight away (an ack, an
   * error, a welcome), or null.
   */
  JsonNode receive(Caller caller, JsonNode envelope);

  /** Waits up to {@code wait} for messages for the device, then returns what there is. */
  List<JsonNode> poll(Caller caller, Duration wait);

  /** Stores a file the device uploads with a command's result; returns its id. */
  UUID upload(Caller caller, UUID commandId, String name, String contentType, byte[] content);

  /** A file a command gave the device to work on. */
  Devices.DeviceFile download(Caller caller, UUID fileId);

  void registerPushToken(Caller caller, String provider, String token);

  /** @param signature the new key signed with the old one, base64 */
  void rotateKey(Caller caller, String newPublicKey, String signature);

  List<DeviceActionSpec> catalog();

  /** A personal device as smart sign-in sees it: who owns it and what it is called. */
  record Owned(UUID userId, String name) {}

  /**
   * The person who paired this device, when a person did. Empty for a shared machine enrolled with a
   * token, which belongs to no one and so can approve no one's sign-in.
   */
  default java.util.Optional<Owned> personalOwner(Caller caller) {
    return java.util.Optional.empty();
  }

  /**
   * Whether {@code signature} (base64) is the device's current key signing {@code text}'s UTF-8
   * bytes.
   */
  default boolean verifySignature(Caller caller, String text, String signature) {
    return false;
  }

  /**
   * A person's own phone as the Jizza app's account screens see it: whose it is, which account it
   * acts in, and what it may do there.
   */
  record Companion(UUID userId, UUID workspaceId, UUID deviceId, String deviceName,
                   PhoneAccess access) {}

  /** Empty for a shared machine enrolled with a token, which has no person behind it. */
  default java.util.Optional<Companion> companion(Caller caller) {
    return java.util.Optional.empty();
  }
}
