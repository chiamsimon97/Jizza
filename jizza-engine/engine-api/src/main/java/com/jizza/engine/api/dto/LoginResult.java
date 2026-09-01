package com.jizza.engine.api.dto;

import java.util.UUID;

/** @param mfaToken non-null only when a second factor is still outstanding. */
public record LoginResult(boolean mfaRequired, String mfaToken, UUID sessionId, UUID userId) {}
