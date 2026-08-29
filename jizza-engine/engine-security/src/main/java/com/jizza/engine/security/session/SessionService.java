package com.jizza.engine.security.session;

import com.jizza.engine.api.dto.SessionDto;
import com.jizza.engine.security.config.SecurityProperties;
import com.jizza.engine.persistence.entity.SessionEntity;
import com.jizza.engine.persistence.repository.SessionRepository;
import java.net.InetAddress;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Server-side sessions (R5): idle <em>and</em> absolute expiry, revocation, "log out everywhere",
 * and an active-session list. Nothing about the session lives in the cookie except its id.
 */
@Service
public class SessionService {

  private final SessionRepository sessions;
  private final SecurityProperties props;

  public SessionService(SessionRepository sessions, SecurityProperties props) {
    this.sessions = sessions;
    this.props = props;
  }

  @Transactional
  public SessionEntity create(UUID userId, String device, InetAddress ip) {
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
    return sessions.save(s);
  }

  /**
   * Validate on every request and slide the idle window. Returns empty for a session that is
   * revoked, idle-expired or past its absolute lifetime — all three are terminal.
   */
  @Transactional
  public Optional<SessionEntity> validateAndTouch(UUID sessionId) {
    return sessions.findById(sessionId).flatMap(s -> {
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
      sessions.save(s);
    });
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

  @Transactional
  public int revokeAll(UUID userId) {
    return sessions.revokeAllForUser(userId);
  }

  @Transactional(readOnly = true)
  public List<SessionDto> list(UUID userId, UUID currentSessionId) {
    return sessions.findByUserIdOrderByIssuedAtDesc(userId).stream()
        .map(s -> new SessionDto(s.getId(), s.getDevice(),
            s.getIp() == null ? null : s.getIp().getHostAddress(),
            s.getIssuedAt(), s.getIdleExpiresAt(), s.getAbsoluteExpiresAt(), s.isRevoked(),
            s.getId().equals(currentSessionId)))
        .toList();
  }
}
