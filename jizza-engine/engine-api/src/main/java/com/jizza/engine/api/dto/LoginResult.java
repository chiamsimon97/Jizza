package com.jizza.engine.api.dto;

import java.util.UUID;

/**
 * @param mfaToken non-null only when a second factor is still outstanding.
 * @param sessionToken the opaque value for the session cookie; non-null only when a session was
 *     issued. It is shown to the caller once and only its hash is stored.
 */
public record LoginResult(boolean mfaRequired, String mfaToken, UUID sessionId, String sessionToken,
                          UUID userId) {}
