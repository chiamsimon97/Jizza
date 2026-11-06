package com.jizza.engine.devices.registry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.crypto.EncryptionService;
import com.jizza.engine.api.device.DeviceActionSpec;
import com.jizza.engine.api.device.DeviceAgentGateway;
import com.jizza.engine.api.device.DeviceCommandDto;
import com.jizza.engine.api.device.DeviceCommandRequest;
import com.jizza.engine.api.device.DeviceDto;
import com.jizza.engine.api.device.DevicePlatform;
import com.jizza.engine.api.device.DeviceStatus;
import com.jizza.engine.api.device.DeviceTarget;
import com.jizza.engine.api.device.Devices;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.enums.NotificationKind;
import com.jizza.engine.api.error.AuthException;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.RateLimitedException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.notification.NotificationBus;
import com.jizza.engine.api.notification.NotificationContext;
import com.jizza.engine.api.storage.StorageProvider;
import com.jizza.engine.api.util.Hashing;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.devices.broker.CommandBroker;
import com.jizza.engine.devices.catalog.DeviceExtras;
import com.jizza.engine.devices.config.DeviceProperties;
import com.jizza.engine.devices.crypto.DeviceKeys;
import com.jizza.engine.devices.crypto.ServerKeys;
import com.jizza.engine.devices.gateway.DeviceHub;
import com.jizza.engine.persistence.entity.DeviceChallengeEntity;
import com.jizza.engine.persistence.entity.DeviceEnrollmentTokenEntity;
import com.jizza.engine.persistence.entity.DeviceEntity;
import com.jizza.engine.persistence.entity.DeviceKeyEntity;
import com.jizza.engine.persistence.entity.DevicePairingEntity;
import com.jizza.engine.persistence.entity.DevicePushTokenEntity;
import com.jizza.engine.persistence.repository.DeviceChallengeRepository;
import com.jizza.engine.persistence.repository.DeviceEnrollmentTokenRepository;
import com.jizza.engine.persistence.repository.DeviceFileRepository;
import com.jizza.engine.persistence.repository.DeviceKeyRepository;
import com.jizza.engine.persistence.repository.DeviceLogRepository;
import com.jizza.engine.persistence.repository.DevicePairingRepository;
import com.jizza.engine.persistence.repository.DevicePushTokenRepository;
import com.jizza.engine.persistence.repository.DeviceRepository;
import com.jizza.engine.persistence.repository.UserRepository;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Paired devices: enrolling them by pairing code or enrollment token, signing them in with their
 * own key, and everything the web app changes about them.
 *
 * <p>What is decided here, because it is the security mechanism: a code or token works once (or
 * for its uses) and only until it expires, only hashes of either are kept, a device signs in only
 * by signing a fresh single-use challenge with the key it enrolled with, and a revoked device's
 * tokens stop working on their next use.
 */
@Service
public class DeviceRegistryService implements Devices {

  private static final Pattern TAG = Pattern.compile("[a-z0-9][a-z0-9_-]{0,31}");
  private static final Set<String> KEY_STORAGES =
      Set.of("tpm", "keychain", "secure_enclave", "keystore", "file", "browser");
  private static final int MAX_ACTIONS = 500;
  private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();

  private final DeviceRepository devices;
  private final DeviceKeyRepository keys;
  private final DevicePairingRepository pairings;
  private final DeviceEnrollmentTokenRepository tokens;
  private final DeviceChallengeRepository challenges;
  private final DevicePushTokenRepository pushTokens;
  private final DeviceLogRepository logs;
  private final DeviceFileRepository files;
  private final UserRepository users;
  private final StorageProvider storage;
  private final EncryptionService encryption;
  private final ServerKeys serverKeys;
  private final DeviceTokens deviceTokens;
  private final DeviceHub hub;
  private final CommandBroker broker;
  private final AuditLog audit;
  private final ObjectProvider<NotificationBus> notifications;
  private final DeviceProperties props;
  private final Clock clock;
  private final AttemptLimiter limiter;
  private final SecureRandom random = new SecureRandom();

  @Autowired
  public DeviceRegistryService(DeviceRepository devices, DeviceKeyRepository keys,
                               DevicePairingRepository pairings,
                               DeviceEnrollmentTokenRepository tokens,
                               DeviceChallengeRepository challenges,
                               DevicePushTokenRepository pushTokens, DeviceLogRepository logs,
                               DeviceFileRepository files, UserRepository users,
                               StorageProvider storage,
                               EncryptionService encryption, ServerKeys serverKeys, DeviceHub hub,
                               CommandBroker broker, AuditLog audit,
                               ObjectProvider<NotificationBus> notifications,
                               DeviceProperties props) {
    this.devices = devices;
    this.keys = keys;
    this.pairings = pairings;
    this.tokens = tokens;
    this.challenges = challenges;
    this.pushTokens = pushTokens;
    this.logs = logs;
    this.files = files;
    this.users = users;
    this.storage = storage;
    this.encryption = encryption;
    this.serverKeys = serverKeys;
    this.deviceTokens = new DeviceTokens(serverKeys);
    this.hub = hub;
    this.broker = broker;
    this.audit = audit;
    this.notifications = notifications;
    this.props = props;
    this.clock = Clock.systemUTC();
    this.limiter = new AttemptLimiter(props.getAuthAttemptsPerMinute(), clock);
  }

  // ── The web app ───────────────────────────────────────────────────────────────

  @Override
  @Transactional(readOnly = true)
  public List<DeviceDto> list(UUID workspaceId, UUID userId) {
    return devices.findByWorkspaceIdOrderByCreatedAtAsc(workspaceId).stream()
        .filter(d -> d.getOwnerUserId() == null || d.getOwnerUserId().equals(userId))
        .filter(d -> !DeviceStatus.REVOKED.name().equalsIgnoreCase(d.getStatus()))
        .map(this::toDto).toList();
  }

  @Override
  @Transactional(readOnly = true)
  public List<DeviceDto> listAll(UUID workspaceId) {
    return devices.findByWorkspaceIdOrderByCreatedAtAsc(workspaceId).stream()
        .filter(d -> !DeviceStatus.REVOKED.name().equalsIgnoreCase(d.getStatus()))
        .map(this::toDto).toList();
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<DeviceDto> find(UUID deviceId) {
    return devices.findById(deviceId).map(this::toDto);
  }

  @Override
  @Transactional
  public PairingCode startPairing(UUID userId, UUID workspaceId, String serverUrl) {
    String code;
    String hash;
    do {
      code = String.format("%06d", random.nextInt(1_000_000));
      hash = pairingHash(code);
    } while (pairings.findByCodeHash(hash).isPresent());
    DevicePairingEntity pairing = new DevicePairingEntity();
    pairing.setUserId(userId);
    pairing.setWorkspaceId(workspaceId);
    pairing.setCodeHash(hash);
    pairing.setExpiresAt(clock.instant().plus(props.getPairingTtl()));
    pairings.save(pairing);
    audit.record(userId, ActorType.USER, null, "device.pairing_started", null,
        Json.obj().put("expiresAt", pairing.getExpiresAt().toString()));
    String qr = "jizza://pair?code=" + code + (serverUrl == null ? ""
        : "&server=" + URLEncoder.encode(serverUrl, StandardCharsets.UTF_8))
        + "&fp=" + serverKeys.fingerprint();
    return new PairingCode(code, pairing.getExpiresAt(), qr, pairing.getId());
  }

  @Override
  public Map<String, String> installers() {
    Map<String, String> out = new LinkedHashMap<>();
    props.getInstallers().forEach((k, v) -> {
      if (v != null && !v.isBlank()) out.put(k.trim().toLowerCase(Locale.ROOT), v.trim());
    });
    return out;
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<PairingStatus> pairingStatus(UUID userId, UUID pairingId) {
    return pairings.findById(pairingId)
        .filter(p -> p.getUserId().equals(userId))
        .map(p -> {
          DeviceDto device = p.getUsedByDeviceId() == null ? null
              : devices.findById(p.getUsedByDeviceId())
                  .filter(d -> status(d) != DeviceStatus.REVOKED).map(this::toDto).orElse(null);
          boolean used = p.getUsedAt() != null;
          return new PairingStatus(p.getId(), p.getExpiresAt(), used,
              !used && !p.getExpiresAt().isAfter(clock.instant()), device);
        });
  }

  @Override
  @Transactional
  public EnrollmentToken createEnrollmentToken(UUID userId, UUID workspaceId, String name,
                                               int uses, Duration validFor, List<String> tags) {
    if (name == null || name.isBlank()) throw new ValidationException("Give the token a name");
    if (uses < 1 || uses > 1000) throw new ValidationException("A token can be used 1 to 1000 times");
    if (validFor == null || validFor.isNegative() || validFor.isZero()
        || validFor.compareTo(Duration.ofDays(365)) > 0) {
      throw new ValidationException("A token can be valid for up to a year");
    }
    byte[] secret = new byte[32];
    random.nextBytes(secret);
    String token = "jzt_" + URL.encodeToString(secret);
    DeviceEnrollmentTokenEntity entity = new DeviceEnrollmentTokenEntity();
    entity.setWorkspaceId(workspaceId);
    entity.setCreatedBy(userId);
    entity.setName(name.trim());
    entity.setTokenHash(tokenHash(token));
    entity.setUsesLeft(uses);
    entity.setExpiresAt(clock.instant().plus(validFor));
    entity.setTags(Json.toNode(normaliseTags(tags)));
    tokens.save(entity);
    audit.record(userId, ActorType.USER, null, "device.enrollment_token_created", entity.getName(),
        Json.obj().put("tokenId", entity.getId().toString()).put("uses", uses));
    return toDto(entity, token);
  }

  @Override
  @Transactional(readOnly = true)
  public List<EnrollmentToken> enrollmentTokens(UUID workspaceId) {
    return tokens.findByWorkspaceIdOrderByCreatedAtDesc(workspaceId).stream()
        .map(t -> toDto(t, null)).toList();
  }

  @Override
  @Transactional
  public void revokeEnrollmentToken(UUID workspaceId, UUID tokenId) {
    DeviceEnrollmentTokenEntity token = tokens.findById(tokenId)
        .filter(t -> t.getWorkspaceId().equals(workspaceId))
        .orElseThrow(() -> new NotFoundException("Enrollment token", tokenId));
    if (token.getRevokedAt() == null) {
      token.setRevokedAt(clock.instant());
      tokens.save(token);
    }
  }

  @Override
  @Transactional
  public DeviceDto update(UUID deviceId, DeviceChanges changes) {
    DeviceEntity device = require(deviceId);
    if (changes.name() != null) device.setName(validName(changes.name()));
    if (changes.tags() != null) device.setTags(Json.toNode(normaliseTags(changes.tags())));
    if (changes.shellEnabled() != null) device.setShellEnabled(changes.shellEnabled());
    if (changes.autoUpdate() != null) device.setAutoUpdate(changes.autoUpdate());
    if (changes.blockedActions() != null) {
      List<String> blocked = changes.blockedActions().stream().filter(Objects::nonNull)
          .map(String::trim).filter(s -> !s.isEmpty()).distinct().limit(MAX_ACTIONS).toList();
      device.setBlockedActions(Json.toNode(blocked));
    }
    if (changes.toolTypes() != null) {
      ObjectNode types = device.getToolTypes() != null && device.getToolTypes().isObject()
          ? (ObjectNode) device.getToolTypes().deepCopy() : Json.obj();
      changes.toolTypes().forEach((tool, type) -> {
        if (tool == null || !tool.startsWith(DeviceActionSpec.EXTENSION_PREFIX) || tool.length() > 120) {
          throw new ValidationException("Only tools a device added itself (x-…) take an action type");
        }
        if (type == null) types.remove(tool);
        else types.put(tool, type.name());
      });
      if (types.size() > 200) throw new ValidationException("Too many tool types for one device");
      device.setToolTypes(types);
    }
    devices.save(device);
    if (changes.shellEnabled() != null || changes.blockedActions() != null) sendPolicy(device);
    return toDto(device);
  }

  @Override
  @Transactional
  public DeviceDto setEnabled(UUID deviceId, boolean enabled) {
    DeviceEntity device = require(deviceId);
    if (status(device) == DeviceStatus.REVOKED) {
      throw new ValidationException("This device was revoked; pair it again instead");
    }
    device.setStatus((enabled ? DeviceStatus.ACTIVE : DeviceStatus.DISABLED).name().toLowerCase(Locale.ROOT));
    devices.save(device);
    if (!enabled) {
      broker.cancelForDevice(deviceId, "The device was disabled");
      hub.disconnect(deviceId, "disabled");
    }
    return toDto(device);
  }

  @Override
  @Transactional
  public DeviceDto revoke(UUID deviceId, String reason) {
    DeviceEntity device = require(deviceId);
    if (status(device) != DeviceStatus.REVOKED) {
      device.setStatus("revoked");
      device.setRevokedAt(clock.instant());
      devices.save(device);
      keys.findFirstByDeviceIdAndRetiredAtIsNullOrderByCreatedAtDesc(deviceId).ifPresent(k -> {
        k.setRetiredAt(clock.instant());
        keys.save(k);
      });
      pushTokens.deleteById(deviceId);
      broker.cancelForDevice(deviceId, "The device was revoked");
      hub.deliver(deviceId, com.jizza.engine.devices.protocol.Protocol.envelope(
          com.jizza.engine.devices.protocol.Protocol.BYE, deviceId,
          Json.obj().put("reason", "revoked"), null));
      hub.disconnect(deviceId, "revoked");
      audit.record(ownerOrEnroller(device), ActorType.USER, null, "device.revoked",
          device.getName(), Json.obj().put("deviceId", deviceId.toString())
              .put("reason", String.valueOf(reason)));
    }
    return toDto(device);
  }

  @Override
  @Transactional
  public int revokeOwnedBy(UUID userId, UUID workspaceId, String reason) {
    int count = 0;
    for (DeviceEntity device : devices.findByOwnerUserIdAndWorkspaceId(userId, workspaceId)) {
      if (status(device) == DeviceStatus.REVOKED) continue;
      revoke(device.getId(), reason);
      count++;
    }
    return count;
  }

  @Override
  public DeviceCommandDto ping(UUID userId, UUID workspaceId, UUID deviceId) {
    return broker.submit(new DeviceCommandRequest(userId, workspaceId, DeviceTarget.device(deviceId),
        "system.ping", 0, Json.obj(), Duration.ofSeconds(30), null, null, null, true));
  }

  @Override
  @Transactional(readOnly = true)
  public List<DeviceLogLine> logs(UUID deviceId, int limit) {
    return logs.findByDeviceIdOrderByTsDesc(deviceId, PageRequest.of(0, Math.max(1, Math.min(limit, 500))))
        .stream().map(l -> new DeviceLogLine(l.getTs(), l.getLevel(), l.getMessage(), l.getCommandId()))
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<DeviceFile> file(UUID fileId) {
    return files.findById(fileId).map(f -> new DeviceFile(f.getId(), f.getDeviceId(),
        f.getCommandId(), f.getName(), f.getContentType(), f.getSize(),
        encryption.decrypt(storage.get(f.getStorageRef()))));
  }

  // ── The device side ───────────────────────────────────────────────────────────

  @Transactional
  public DeviceAgentGateway.Enrolled enroll(DeviceAgentGateway.Enrollment in, String remoteAddress) {
    if (!limiter.tryAcquire("enroll:" + remoteAddress)) {
      throw new RateLimitedException("Too many enrollment attempts; wait a minute and try again");
    }
    boolean byCode = in.code() != null && !in.code().isBlank();
    boolean byToken = in.enrollmentToken() != null && !in.enrollmentToken().isBlank();
    if (byCode == byToken) {
      throw new ValidationException("Enroll with either a pairing code or an enrollment token");
    }
    PublicKey publicKey = DeviceKeys.parse(in.publicKey());
    int protocol = negotiate(in.protocol());
    String storage = in.keyStorage() == null ? "file"
        : in.keyStorage().trim().toLowerCase(Locale.ROOT);
    if (!KEY_STORAGES.contains(storage)) storage = "file";

    DeviceEntity device = new DeviceEntity();
    String fallbackName = in.hostname() == null || in.hostname().isBlank() ? "New device"
        : limit(in.hostname(), 80);
    device.setName(validName(in.name() == null || in.name().isBlank() ? fallbackName : in.name()));
    device.setPlatform(DevicePlatform.from(in.platform()).wire());
    device.setOsVersion(limit(in.osVersion(), 64));
    device.setAppVersion(limit(in.appVersion(), 64));
    device.setHostname(limit(in.hostname(), 255));
    device.setProtocolVersion(protocol);
    device.setStatus("active");
    device.setKeyStorage(storage);
    device.setActions(Json.mapper().createArrayNode());
    device.setBlockedActions(Json.mapper().createArrayNode());
    device.setToolTypes(Json.obj());
    device.setLastIp(limit(remoteAddress, 64));

    String action;
    if (byCode) {
      DevicePairingEntity pairing = pairings.findByCodeHash(pairingHash(in.code().trim()))
          .filter(p -> p.getUsedAt() == null && p.getExpiresAt().isAfter(clock.instant()))
          .orElseThrow(() -> new AuthException("pairing_code",
              "That pairing code is not valid. Codes work once, for 10 minutes."));
      device.setWorkspaceId(pairing.getWorkspaceId());
      device.setOwnerUserId(pairing.getUserId());
      device.setEnrolledBy(pairing.getUserId());
      device.setTags(Json.mapper().createArrayNode());
      devices.save(device);
      pairing.setUsedAt(clock.instant());
      pairing.setUsedByDeviceId(device.getId());
      pairings.save(pairing);
      action = "device.paired";
    } else {
      DeviceEnrollmentTokenEntity token = tokens.findByTokenHash(tokenHash(in.enrollmentToken().trim()))
          .filter(t -> t.getRevokedAt() == null && t.getUsesLeft() > 0
              && t.getExpiresAt().isAfter(clock.instant()))
          .orElseThrow(() -> new AuthException("enrollment_token",
              "That enrollment token is not valid, has been used up or has expired."));
      device.setWorkspaceId(token.getWorkspaceId());
      device.setEnrolledBy(token.getCreatedBy());
      device.setTags(token.getTags() == null ? Json.mapper().createArrayNode() : token.getTags());
      devices.save(device);
      token.setUsesLeft(token.getUsesLeft() - 1);
      tokens.save(token);
      action = "device.enrolled";
    }
    DeviceKeyEntity key = new DeviceKeyEntity();
    key.setDeviceId(device.getId());
    key.setPublicKey(DeviceKeys.encode(publicKey));
    key.setKeyStorage(storage);
    keys.save(key);

    UUID who = ownerOrEnroller(device);
    audit.record(who, ActorType.USER, null, action, device.getName(),
        Json.obj().put("deviceId", device.getId().toString()).put("platform", device.getPlatform())
            .put("keyStorage", storage).put("ip", String.valueOf(remoteAddress)));
    if (who != null) {
      NotificationBus bus = notifications.getIfAvailable();
      if (bus != null) {
        bus.raise(NotificationKind.INFO, null, NotificationContext.info(who,
            (byCode ? "New device paired: " : "New device enrolled: ") + device.getName()
                + ". If this wasn't you, revoke it under Settings > Devices."));
      }
    }
    return new DeviceAgentGateway.Enrolled(device.getId(), serverKeys.publicKey(), protocol);
  }

  @Transactional
  public DeviceAgentGateway.Challenge challenge(UUID deviceId) {
    DeviceEntity device = devices.findById(deviceId)
        .filter(d -> status(d) == DeviceStatus.ACTIVE)
        .orElseThrow(() -> new AuthException("device", "This device is not paired, or was disabled or revoked"));
    challenges.deleteExpired(clock.instant());
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    DeviceChallengeEntity challenge = new DeviceChallengeEntity();
    challenge.setId(URL.encodeToString(bytes));
    challenge.setDeviceId(device.getId());
    challenge.setExpiresAt(clock.instant().plus(props.getChallengeTtl()));
    challenges.save(challenge);
    return new DeviceAgentGateway.Challenge(challenge.getId(), challenge.getExpiresAt());
  }

  @Transactional
  public DeviceAgentGateway.AccessToken token(UUID deviceId, String nonce, String signature,
                                              String remoteAddress) {
    if (!limiter.tryAcquire("token:" + remoteAddress)) {
      throw new RateLimitedException("Too many sign-in attempts; wait a minute and try again");
    }
    DeviceChallengeEntity challenge = nonce == null ? null : challenges.findById(nonce).orElse(null);
    if (challenge != null) challenges.delete(challenge);
    DeviceEntity device = devices.findById(deviceId).orElse(null);
    boolean valid = challenge != null && challenge.getDeviceId().equals(deviceId)
        && challenge.getExpiresAt().isAfter(clock.instant()) && device != null
        && status(device) == DeviceStatus.ACTIVE;
    if (valid) {
      DeviceKeyEntity key = keys.findFirstByDeviceIdAndRetiredAtIsNullOrderByCreatedAtDesc(deviceId)
          .orElse(null);
      valid = key != null && DeviceKeys.verify(DeviceKeys.parse(key.getPublicKey()), nonce, signature);
    }
    if (!valid) {
      if (device != null) {
        audit.record(ownerOrEnroller(device), ActorType.SYSTEM, null, "device.sign_in_failed",
            device.getName(), Json.obj().put("deviceId", deviceId.toString())
                .put("ip", String.valueOf(remoteAddress)));
      }
      throw new AuthException("device_sign_in", "The device could not sign in. Ask for a new "
          + "challenge and sign it with the device's key.");
    }
    device.setLastIp(limit(remoteAddress, 64));
    device.setLastSeenAt(clock.instant());
    devices.save(device);
    Instant expires = clock.instant().plus(props.getTokenTtl());
    return new DeviceAgentGateway.AccessToken(deviceTokens.issue(deviceId, expires), expires);
  }

  @Transactional(readOnly = true)
  public DeviceAgentGateway.Caller authenticate(String bearer) {
    DeviceTokens.Parsed parsed = deviceTokens.parse(bearer)
        .orElseThrow(() -> new AuthException("device_token", "A valid device token is required"));
    if (!parsed.expiresAt().isAfter(clock.instant())) {
      throw new AuthException("device_token_expired", "The device token has expired; sign in again");
    }
    DeviceEntity device = devices.findById(parsed.deviceId())
        .orElseThrow(() -> new AuthException("device_token", "A valid device token is required"));
    DeviceStatus status = status(device);
    if (status == DeviceStatus.REVOKED) {
      throw new AuthException("device_revoked", "This device was revoked");
    }
    if (status == DeviceStatus.DISABLED) {
      throw new AuthException("device_disabled", "This device is disabled");
    }
    return new DeviceAgentGateway.Caller(device.getId(), device.getOwnerUserId() != null
        ? device.getOwnerUserId() : device.getEnrolledBy(), device.getWorkspaceId());
  }

  @Override
  @Transactional(readOnly = true)
  public com.jizza.engine.api.device.PhoneAccess phoneAccess(UUID deviceId) {
    return com.jizza.engine.api.device.PhoneAccess.from(require(deviceId).getPhoneAccess());
  }

  @Override
  @Transactional
  public com.jizza.engine.api.device.PhoneAccess setPhoneAccess(
      UUID deviceId, com.jizza.engine.api.device.PhoneAccess access) {
    if (access == null) throw new ValidationException("Choose read, approve or full");
    DeviceEntity device = require(deviceId);
    if (device.getOwnerUserId() == null) {
      throw new ValidationException("Only a phone paired by a person has phone access");
    }
    String before = device.getPhoneAccess();
    device.setPhoneAccess(access.code());
    device.setUpdatedAt(clock.instant());
    devices.save(device);
    audit.record(device.getOwnerUserId(), ActorType.USER, null, "device.phone_access_changed",
        device.getId().toString(), Json.obj().put("from", before).put("to", access.code()));
    return access;
  }

  /** Checks a signature by the device's current key; false for no key or a bad signature. */
  @Transactional(readOnly = true)
  public boolean verifySignature(UUID deviceId, String text, String signature) {
    if (text == null || signature == null || signature.isBlank()) return false;
    return keys.findFirstByDeviceIdAndRetiredAtIsNullOrderByCreatedAtDesc(deviceId)
        .map(k -> DeviceKeys.verify(DeviceKeys.parse(k.getPublicKey()), text, signature))
        .orElse(false);
  }

  @Transactional
  public void rotateKey(UUID deviceId, String newPublicKey, String signature) {
    DeviceKeyEntity current = keys.findFirstByDeviceIdAndRetiredAtIsNullOrderByCreatedAtDesc(deviceId)
        .orElseThrow(() -> new AuthException("device", "This device has no key to rotate"));
    PublicKey next = DeviceKeys.parse(newPublicKey);
    String encoded = DeviceKeys.encode(next);
    if (!DeviceKeys.verify(DeviceKeys.parse(current.getPublicKey()), newPublicKey, signature)) {
      throw new AuthException("bad_signature", "Sign the new key with the current one");
    }
    current.setRetiredAt(clock.instant());
    keys.save(current);
    DeviceKeyEntity key = new DeviceKeyEntity();
    key.setDeviceId(deviceId);
    key.setPublicKey(encoded);
    key.setKeyStorage(current.getKeyStorage());
    keys.save(key);
    DeviceEntity device = require(deviceId);
    audit.record(ownerOrEnroller(device), ActorType.SYSTEM, null, "device.key_rotated",
        device.getName(), Json.obj().put("deviceId", deviceId.toString()));
  }

  @Transactional
  public void registerPushToken(UUID deviceId, String provider, String token) {
    String name = provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
    if (!name.equals("apns") && !name.equals("fcm")) {
      throw new ValidationException("Push provider must be apns or fcm");
    }
    if (token == null || token.isBlank() || token.length() > 4096) {
      throw new ValidationException("Give the push token");
    }
    DevicePushTokenEntity entity = pushTokens.findById(deviceId).orElseGet(() -> {
      DevicePushTokenEntity fresh = new DevicePushTokenEntity();
      fresh.setId(deviceId);
      return fresh;
    });
    entity.setProvider(name);
    entity.setToken(encryption.encryptString(token.trim()));
    entity.setUpdatedAt(clock.instant());
    pushTokens.save(entity);
  }

  /** What a device said in {@code hello}: versions it runs now. */
  @Transactional
  public void recordHello(UUID deviceId, JsonNode body) {
    DeviceEntity device = require(deviceId);
    if (body.hasNonNull("appVersion")) device.setAppVersion(limit(body.get("appVersion").asText(), 64));
    if (body.hasNonNull("osVersion")) device.setOsVersion(limit(body.get("osVersion").asText(), 64));
    if (body.has("protocol")) {
      List<Integer> versions = new ArrayList<>();
      body.get("protocol").forEach(v -> versions.add(v.asInt()));
      device.setProtocolVersion(negotiate(versions));
    }
    device.setLastSeenAt(clock.instant());
    devices.save(device);
  }

  /** Keeps the device's newest manifest: its capabilities with local limits, and its actions. */
  @Transactional
  public void recordManifest(UUID deviceId, JsonNode body) {
    DeviceEntity device = require(deviceId);
    JsonNode capabilities = body.path("capabilities");
    if (!capabilities.isObject()) {
      throw new ValidationException("A manifest needs a capabilities object");
    }
    JsonNode declared = body.path("actions");
    if (!declared.isArray()) throw new ValidationException("A manifest needs an actions list");
    if (declared.size() > MAX_ACTIONS) {
      throw new ValidationException("A manifest can declare at most " + MAX_ACTIONS + " actions");
    }
    Set<String> actions = new LinkedHashSet<>();
    for (JsonNode a : declared) {
      String ref = a.asText("").trim();
      if (!ref.matches("[a-zA-Z0-9_.-]{1,96}(@[0-9]{1,4})?")) {
        throw new ValidationException("'" + ref + "' is not an action name such as browser.open@1");
      }
      actions.add(ref);
    }
    ArrayNode tools = DeviceExtras.tools(body.get("tools"));
    // A tool the device describes is one it declares, so it routes like any other action.
    for (JsonNode t : tools) {
      actions.removeIf(a -> a.equals(t.get("action").asText()) || a.startsWith(t.get("action").asText() + "@"));
      actions.add(t.get("action").asText() + "@" + t.get("version").asInt());
    }
    if (actions.size() > MAX_ACTIONS) {
      throw new ValidationException("A manifest can declare at most " + MAX_ACTIONS + " actions");
    }
    ObjectNode manifest = Json.obj();
    manifest.set("capabilities", capabilities);
    manifest.set("limits", body.path("limits").isObject() ? body.get("limits") : Json.obj());
    manifest.set("tools", tools);
    manifest.set("items", DeviceExtras.items(body.get("items")));
    device.setManifest(manifest);
    ArrayNode list = Json.mapper().createArrayNode();
    actions.forEach(list::add);
    device.setActions(list);
    devices.save(device);
  }

  /** Sends the device what it must enforce locally besides its own limits. */
  public void sendPolicy(DeviceEntity device) {
    ObjectNode body = Json.obj();
    body.put("deviceId", device.getId().toString());
    body.put("shellEnabled", device.isShellEnabled());
    body.set("blockedActions", device.getBlockedActions());
    body.put("issuedAt", clock.instant().toString());
    var env = com.jizza.engine.devices.protocol.Protocol.envelope(
        com.jizza.engine.devices.protocol.Protocol.POLICY, device.getId(), body, null);
    env.put("sig", serverKeys.sign(com.jizza.engine.devices.protocol.Canonical.of(body)));
    hub.deliver(device.getId(), env);
  }

  public DeviceDto toDto(DeviceEntity d) {
    List<String> tags = new ArrayList<>();
    if (d.getTags() != null) d.getTags().forEach(t -> tags.add(t.asText()));
    List<String> actions = new ArrayList<>();
    if (d.getActions() != null) d.getActions().forEach(a -> actions.add(a.asText()));
    List<String> blocked = new ArrayList<>();
    if (d.getBlockedActions() != null) d.getBlockedActions().forEach(a -> blocked.add(a.asText()));
    DeviceStatus status = status(d);
    boolean online = status == DeviceStatus.ACTIVE && hub.online(d.getId());
    Instant seen = hub.lastSeen(d.getId());
    if (seen == null || (d.getLastSeenAt() != null && d.getLastSeenAt().isAfter(seen))) {
      seen = d.getLastSeenAt();
    }
    JsonNode capabilities = d.getManifest() == null ? Json.obj() : d.getManifest().path("capabilities");
    return new DeviceDto(d.getId(), d.getWorkspaceId(), d.getOwnerUserId(), d.getName(),
        DevicePlatform.from(d.getPlatform()), d.getOsVersion(), d.getAppVersion(),
        d.getProtocolVersion(), status, tags, d.getKeyStorage(), online,
        online ? hub.state(d.getId()) : "offline", capabilities, actions, d.isShellEnabled(),
        blocked, seen, d.getCreatedAt(), DeviceExtras.specs(d),
        d.getManifest() != null && d.getManifest().path("items").isObject()
            ? d.getManifest().get("items") : Json.obj(),
        d.getHostname(), d.isAutoUpdate(), d.getEnrolledBy(),
        d.getEnrolledBy() == null ? null
            : users.findById(d.getEnrolledBy()).map(u -> u.getEmail()).orElse(null),
        d.getUpdatedAt());
  }

  private EnrollmentToken toDto(DeviceEnrollmentTokenEntity t, String token) {
    List<String> tags = new ArrayList<>();
    if (t.getTags() != null) t.getTags().forEach(x -> tags.add(x.asText()));
    return new EnrollmentToken(t.getId(), t.getName(), token, t.getUsesLeft(), t.getExpiresAt(),
        tags, t.getCreatedBy(), t.getCreatedAt(), t.getRevokedAt() != null);
  }

  private DeviceEntity require(UUID deviceId) {
    return devices.findById(deviceId).orElseThrow(() -> new NotFoundException("Device", deviceId));
  }

  static DeviceStatus status(DeviceEntity d) {
    return DeviceStatus.valueOf(d.getStatus().toUpperCase(Locale.ROOT));
  }

  private static UUID ownerOrEnroller(DeviceEntity d) {
    return d.getOwnerUserId() != null ? d.getOwnerUserId() : d.getEnrolledBy();
  }

  private int negotiate(List<Integer> offered) {
    if (offered == null || offered.isEmpty()) return props.currentProtocol();
    int best = 0;
    for (int v : props.getProtocolVersions()) if (offered.contains(v)) best = Math.max(best, v);
    if (best == 0) {
      throw new ValidationException("This server speaks protocol version "
          + props.currentProtocol() + "; update the Jizza Agent app");
    }
    return best;
  }

  private static String validName(String name) {
    String trimmed = name.trim();
    if (trimmed.isEmpty() || trimmed.length() > 80) {
      throw new ValidationException("A device name is 1 to 80 characters");
    }
    return trimmed;
  }

  static List<String> normaliseTags(List<String> tags) {
    if (tags == null) return List.of();
    List<String> out = new ArrayList<>();
    for (String tag : tags) {
      if (tag == null || tag.isBlank()) continue;
      String t = tag.trim().toLowerCase(Locale.ROOT);
      if (!TAG.matcher(t).matches()) {
        throw new ValidationException("Tags are letters, digits, - and _, up to 32 characters: '"
            + tag + "'");
      }
      if (!out.contains(t)) out.add(t);
    }
    if (out.size() > 10) throw new ValidationException("A device can have at most 10 tags");
    return out;
  }

  private static String limit(String value, int max) {
    if (value == null) return null;
    String trimmed = value.trim();
    return trimmed.length() > max ? trimmed.substring(0, max) : trimmed;
  }

  private static String pairingHash(String code) {
    return Hashing.sha256Hex("device-pairing:" + code);
  }

  private static String tokenHash(String token) {
    return Hashing.sha256Hex("device-enrollment:" + token);
  }
}
