package com.jizza.engine.devices.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.devices.config.DeviceProperties;
import com.jizza.engine.devices.protocol.Canonical;
import com.jizza.engine.devices.registry.DeviceRegistryService;
import com.jizza.engine.persistence.repository.DeviceRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Device-signed messages (web automation plan 05, P1). A device signs each envelope with its own
 * key (P-256 for the browser extension) over the canonical JSON of the envelope without
 * {@code sig}. A valid signature is checked against the key the device paired with; a message seen
 * before (same id) is a replay and is not acted on again. Once a device has signed one message it
 * must sign them all, so a stolen access token alone cannot forge results, recording steps or
 * answers for it. {@code jizza.devices.require-signed-messages} refuses unsigned messages from
 * every device.
 */
@Component
public class MessageSignatures {

  /** What to do with a message. */
  public enum Verdict { ACCEPT, REPLAY, REFUSE_UNSIGNED, REFUSE_BAD_SIGNATURE, REFUSE_STALE }

  /** Signed messages older than this are refused: long enough for an outbox to catch up. */
  static final Duration MAX_AGE = Duration.ofHours(24);
  /** Clock skew allowed for a message from the future. */
  static final Duration MAX_SKEW = Duration.ofMinutes(5);
  private static final int SEEN_CAPACITY = 50_000;

  private final DeviceRegistryService registry;
  private final DeviceRepository devices;
  private final DeviceProperties props;
  private final Clock clock;
  /** Devices known to sign, so the database is asked once per device per server start. */
  private final Set<UUID> signing = ConcurrentHashMap.newKeySet();
  /** Recently seen signed message ids, oldest first. */
  private final Map<String, Boolean> seen = Collections.synchronizedMap(
      new LinkedHashMap<>(1024, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
          return size() > SEEN_CAPACITY;
        }
      });

  @Autowired
  public MessageSignatures(DeviceRegistryService registry, DeviceRepository devices,
                           DeviceProperties props) {
    this(registry, devices, props, Clock.systemUTC());
  }

  MessageSignatures(DeviceRegistryService registry, DeviceRepository devices,
                    DeviceProperties props, Clock clock) {
    this.registry = registry;
    this.devices = devices;
    this.props = props;
    this.clock = clock;
  }

  @Transactional
  public Verdict check(UUID deviceId, JsonNode envelope) {
    JsonNode sig = envelope.get("sig");
    if (sig == null || !sig.isTextual() || sig.asText().isBlank()) {
      return mustSign(deviceId) ? Verdict.REFUSE_UNSIGNED : Verdict.ACCEPT;
    }
    ObjectNode unsigned = ((ObjectNode) envelope).deepCopy();
    unsigned.remove("sig");
    if (!registry.verifySignature(deviceId, Canonical.of(unsigned), sig.asText())) {
      return Verdict.REFUSE_BAD_SIGNATURE;
    }
    Instant ts = parse(envelope.path("ts").asText(null));
    Instant now = clock.instant();
    if (ts == null || ts.isBefore(now.minus(MAX_AGE)) || ts.isAfter(now.plus(MAX_SKEW))) {
      return Verdict.REFUSE_STALE;
    }
    markSigning(deviceId);
    String key = deviceId + ":" + envelope.path("id").asText("");
    return seen.putIfAbsent(key, Boolean.TRUE) == null ? Verdict.ACCEPT : Verdict.REPLAY;
  }

  private boolean mustSign(UUID deviceId) {
    if (props.isRequireSignedMessages() || signing.contains(deviceId)) return true;
    boolean signs = devices.findById(deviceId).map(d -> d.isSignsMessages()).orElse(false);
    if (signs) signing.add(deviceId);
    return signs;
  }

  private void markSigning(UUID deviceId) {
    if (signing.contains(deviceId)) return;
    devices.findById(deviceId).ifPresent(device -> {
      if (!device.isSignsMessages()) {
        device.setSignsMessages(true);
        devices.save(device);
      }
    });
    signing.add(deviceId);
  }

  private static Instant parse(String value) {
    if (value == null) return null;
    try {
      return Instant.parse(value);
    } catch (DateTimeParseException e) {
      try {
        return java.time.OffsetDateTime.parse(value).toInstant();
      } catch (DateTimeParseException again) {
        return null;
      }
    }
  }
}
