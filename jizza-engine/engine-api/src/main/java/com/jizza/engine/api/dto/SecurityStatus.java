package com.jizza.engine.api.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * What the signed-in user's account protection looks like right now.
 *
 * @param mfaRequired the deployment requires two-step sign-in for every account
 * @param enrollmentRequired this session may only reach the two-step setup until it is finished
 * @param recoveryCodesRemaining unused one-time recovery codes left
 */
public record SecurityStatus(UUID userId, String email, boolean mfaEnabled, Instant mfaEnabledAt,
                             boolean mfaRequired, boolean enrollmentRequired,
                             long recoveryCodesRemaining) {}
