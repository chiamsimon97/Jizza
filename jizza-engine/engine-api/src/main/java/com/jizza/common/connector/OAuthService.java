package com.jizza.common.connector;

import com.jizza.common.dto.ConnectionDto;
import java.net.URI;
import java.util.UUID;

/** The hardened authorization-code handshake (R24). */
public interface OAuthService {

  /** Build the provider authorize URL, minting a single-use expiring state bound to the session. */
  URI startAuth(UUID connectionId, UUID sessionId);

  /** Exchange the code. Rejects unknown, expired, replayed or session-mismatched state. */
  ConnectionDto completeCallback(String state, String code);

  /** Refresh (with rotation) when the access token is near expiry; transparent to callers. */
  ConnectionDto refreshIfNeeded(UUID connectionId);

  /** Revoke at the provider where configured, then clear stored tokens. */
  void revoke(UUID connectionId);

  TestResult test(UUID connectionId);

  record TestResult(boolean ok, int httpStatus, String message) {}
}
