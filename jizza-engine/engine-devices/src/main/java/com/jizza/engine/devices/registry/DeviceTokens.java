package com.jizza.engine.devices.registry;

import com.jizza.engine.devices.crypto.ServerKeys;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * Access tokens: {@code jzd_<payload>.<mac>}, where the payload is the device id, the expiry and a
 * random part, and the mac is HMAC-SHA256 under the server's token secret. Nothing is stored; the
 * device's status is checked on every use, so revoking a device ends its tokens at once.
 */
public class DeviceTokens {

  static final String PREFIX = "jzd_";
  private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

  public record Parsed(UUID deviceId, Instant expiresAt) {}

  private final ServerKeys keys;
  private final SecureRandom random = new SecureRandom();

  public DeviceTokens(ServerKeys keys) {
    this.keys = keys;
  }

  public String issue(UUID deviceId, Instant expiresAt) {
    byte[] nonce = new byte[8];
    random.nextBytes(nonce);
    ByteBuffer payload = ByteBuffer.allocate(32);
    payload.putLong(deviceId.getMostSignificantBits()).putLong(deviceId.getLeastSignificantBits())
        .putLong(expiresAt.getEpochSecond()).put(nonce);
    byte[] bytes = payload.array();
    return PREFIX + ENCODER.encodeToString(bytes) + "." + ENCODER.encodeToString(keys.hmac(bytes));
  }

  /** The device and expiry of a well-formed, correctly signed token; empty otherwise. */
  public Optional<Parsed> parse(String token) {
    if (token == null || !token.startsWith(PREFIX)) return Optional.empty();
    String[] parts = token.substring(PREFIX.length()).split("\\.", -1);
    if (parts.length != 2) return Optional.empty();
    try {
      byte[] payload = DECODER.decode(parts[0]);
      byte[] mac = DECODER.decode(parts[1]);
      if (payload.length != 32 || !ServerKeys.constantTimeEquals(keys.hmac(payload), mac)) {
        return Optional.empty();
      }
      ByteBuffer buffer = ByteBuffer.wrap(payload);
      UUID deviceId = new UUID(buffer.getLong(), buffer.getLong());
      Instant expiresAt = Instant.ofEpochSecond(buffer.getLong());
      return Optional.of(new Parsed(deviceId, expiresAt));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }
}
