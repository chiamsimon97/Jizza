package com.jizza.security.session;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Holds the short window between "password accepted" and "TOTP accepted". Deliberately in memory
 * and short-lived: a half-authenticated login is not something worth persisting, and no session
 * cookie is issued until the second factor succeeds.
 */
@Component
public class PendingMfaStore {

  private static final Duration TTL = Duration.ofMinutes(5);

  private final Map<String, Pending> pending = new ConcurrentHashMap<>();
  private final SecureRandom random = new SecureRandom();

  private record Pending(UUID userId, Instant expiresAt) {}

  public String open(UUID userId) {
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    pending.put(token, new Pending(userId, Instant.now().plus(TTL)));
    return token;
  }

  /** Single-use: consuming a token removes it, so a replayed token fails. */
  public Optional<UUID> consume(String token) {
    sweep();
    Pending p = pending.remove(token);
    if (p == null || Instant.now().isAfter(p.expiresAt())) return Optional.empty();
    return Optional.of(p.userId());
  }

  private void sweep() {
    Instant now = Instant.now();
    pending.entrySet().removeIf(e -> now.isAfter(e.getValue().expiresAt()));
  }
}
