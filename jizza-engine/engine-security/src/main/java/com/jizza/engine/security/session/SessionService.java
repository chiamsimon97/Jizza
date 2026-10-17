package com.jizza.engine.security.session;

import com.jizza.engine.api.dto.SessionDto;
import com.jizza.engine.api.util.Hashing;
import com.jizza.engine.security.config.SecurityProperties;
import com.jizza.engine.persistence.entity.SessionEntity;
import com.jizza.engine.persistence.repository.SessionRepository;
import java.net.InetAddress;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Server-side sessions (R5): idle <em>and</em> absolute expiry, revocation, "log out everywhere",
 * and an active-session list.
 *
 * <p>The cookie holds a 256-bit random token and the row holds only its SHA-256, so neither a
 * database copy nor a guessed row id can be replayed as a live session.
 */
@Service
public class SessionService {

  private final SessionRepository sessions;
  private final SecurityProperties props;
  private final SecureRandom random = new SecureRandom();

  public SessionService(SessionRepository sessions, SecurityProperties props) {
    this.sessions = sessions;
    this.props = props;
  }

  /**
   * @param mfaSatisfied whether the sign-in that created it passed the second factor
   */
  @Transactional
  public IssuedSession create(UUID userId, String device, InetAddress ip, boolean mfaSatisfied) {
    return create(userId, device, ip, mfaSatisfied, null);
  }

  /** Opens a session acting in {@code activeWorkspaceId}. */
  @Transactional
  public IssuedSession create(UUID userId, String device, InetAddress ip, boolean mfaSatisfied,
                              UUID activeWorkspaceId) {
    byte[] raw = new byte[32];
    random.nextBytes(raw);
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);

    Instant now = Instant.now();
    SessionEntity s = new SessionEntity();
    s.setUserId(userId);
    s.setDevice(device);
    s.setIp(ip);
    s.setIssuedAt(now);
    s.setLastSeenAt(now);
    s.setIdleExpiresAt(now.plus(props.getSession().getIdleTimeout()));
    s.setAbsoluteExpiresAt(now.plus(props.getSession().getAbsoluteTimeout()));
    s.setRevoked(false);
    s.setTokenHash(Hashing.sha256Hex(token));
    s.setMfaSatisfied(mfaSatisfied);
    s.setActiveWorkspaceId(activeWorkspaceId);
    return new IssuedSession(sessions.save(s), token);
  }

  /**
   * Switches accounts by replacing the session: a new token acting in {@code workspaceId}, with the
   * same person, device and second-factor state, and the old session revoked. A fresh token on every
   * change of account is the same defence against session fixation as on sign-in; step-up freshness
   * deliberately does not carry over.
   */
  @Transactional
  public IssuedSession switchWorkspace(UUID sessionId, UUID workspaceId) {
    SessionEntity old = sessions.findById(sessionId)
        .filter(s -> !s.isRevoked())
        .orElseThrow(() -> new com.jizza.engine.api.error.AuthException("Not authenticated"));
    IssuedSession issued = create(old.getUserId(), old.getDevice(), old.getIp(),
        old.isMfaSatisfied(), workspaceId);
    old.setRevoked(true);
    sessions.save(old);
    return issued;
  }

  /** Points a session at another account without reissuing it (fallback after losing access). */
  @Transactional
  public void setActiveWorkspace(UUID sessionId, UUID workspaceId) {
    sessions.findById(sessionId).ifPresent(s -> {
      s.setActiveWorkspaceId(workspaceId);
      sessions.save(s);
    });
  }

  /** Moves a person's sessions acting in {@code workspaceId} to {@code fallback}. */
  @Transactional
  public int moveOffWorkspace(UUID userId, UUID workspaceId, UUID fallback) {
    return sessions.moveOffWorkspace(userId, workspaceId, fallback);
  }

  /**
   * Validate on every request and slide the idle window. Returns empty for a token that matches no
   * session, or a session that is revoked, idle-expired or past its absolute lifetime -- all three
   * are terminal.
   */
  @Transactional
  public Optional<SessionEntity> validateAndTouch(String token) {
    if (token == null || token.isBlank()) return Optional.empty();
    return sessions.findByTokenHash(Hashing.sha256Hex(token)).flatMap(s -> {
      Instant now = Instant.now();
      if (s.isRevoked() || now.isAfter(s.getIdleExpiresAt()) || now.isAfter(s.getAbsoluteExpiresAt())) {
        return Optional.empty();
      }
      s.setLastSeenAt(now);
      s.setIdleExpiresAt(now.plus(props.getSession().getIdleTimeout()));
      return Optional.of(sessions.save(s));
    });
  }

  @Transactional
  public void markStepUp(UUID sessionId) {
    sessions.findById(sessionId).ifPresent(s -> {
      s.setStepUpAt(Instant.now());
      s.setStepUpFailures(0);
      sessions.save(s);
    });
  }

  /**
   * Counts a wrong step-up answer and revokes the session once it reaches the limit. Runs in its
   * own transaction because the caller fails right after.
   *
   * @return true when this failure revoked the session
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean recordStepUpFailure(UUID sessionId) {
    return sessions.findById(sessionId).map(s -> {
      int failures = s.getStepUpFailures() + 1;
      s.setStepUpFailures(failures);
      boolean revoke = failures >= props.getStepUp().getMaxFailures();
      if (revoke) s.setRevoked(true);
      sessions.save(s);
      return revoke;
    }).orElse(false);
  }

  /** The session has now passed (or set up) the second factor. */
  @Transactional
  public void markMfaSatisfied(UUID sessionId) {
    sessions.findById(sessionId).ifPresent(s -> {
      s.setMfaSatisfied(true);
      sessions.save(s);
    });
  }

  @Transactional(readOnly = true)
  public boolean mfaSatisfied(UUID sessionId) {
    return sessions.findById(sessionId).map(SessionEntity::isMfaSatisfied).orElse(false);
  }

  /** True when this session re-authenticated recently enough for a high-risk route (R4). */
  public boolean stepUpFresh(UUID sessionId) {
    return sessions.findById(sessionId)
        .map(SessionEntity::getStepUpAt)
        .filter(at -> at.isAfter(Instant.now().minus(props.getStepUp().getFreshness())))
        .isPresent();
  }

  @Transactional
  public void revoke(UUID sessionId) {
    sessions.findById(sessionId).ifPresent(s -> {
      s.setRevoked(true);
      sessions.save(s);
    });
  }

  /** Revokes one of the user's sessions. Another user's session id is silently ignored. */
  @Transactional
  public boolean revoke(UUID userId, UUID sessionId) {
    return sessions.findById(sessionId)
        .filter(s -> s.getUserId().equals(userId))
        .map(s -> {
          s.setRevoked(true);
          sessions.save(s);
          return true;
        })
        .orElse(false);
  }

  @Transactional
  public int revokeAll(UUID userId) {
    return sessions.revokeAllForUser(userId);
  }

  @Transactional
  public int revokeAllExcept(UUID userId, UUID keep) {
    return sessions.revokeAllForUserExcept(userId, keep);
  }

  @Transactional(readOnly = true)
  public List<SessionDto> list(UUID userId, UUID currentSessionId) {
    Instant now = Instant.now();
    return sessions.findByUserIdOrderByIssuedAtDesc(userId).stream()
        .filter(s -> !s.isRevoked() && now.isBefore(s.getIdleExpiresAt())
            && now.isBefore(s.getAbsoluteExpiresAt()))
        .map(s -> new SessionDto(s.getId(), s.getDevice(),
            s.getIp() == null ? null : s.getIp().getHostAddress(),
            s.getIssuedAt(), s.getIdleExpiresAt(), s.getAbsoluteExpiresAt(), s.isRevoked(),
            s.getId().equals(currentSessionId)))
        .toList();
  }
}
