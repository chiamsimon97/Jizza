package com.jizza.security.session;

import java.time.Instant;
import java.util.UUID;

/**
 * The authenticated caller. Carries the session id so revocation and step-up freshness can be
 * evaluated per request rather than trusted from a token.
 */
public record JizzaPrincipal(UUID userId, UUID sessionId, Instant stepUpAt) {}
