package com.jizza.engine.security.session;

import java.time.Instant;
import java.util.UUID;

/**
 * The authenticated caller. Carries the session id so revocation and step-up freshness can be
 * evaluated per request rather than trusted from a token.
 *
 * @param mfaSatisfied the session passed the second factor, or set it up
 * @param activeWorkspaceId the account the session is acting in (null only before resolution)
 */
public record JizzaPrincipal(UUID userId, UUID sessionId, Instant stepUpAt, boolean mfaSatisfied,
                             UUID activeWorkspaceId) {

  public JizzaPrincipal(UUID userId, UUID sessionId, Instant stepUpAt, boolean mfaSatisfied) {
    this(userId, sessionId, stepUpAt, mfaSatisfied, null);
  }
}
