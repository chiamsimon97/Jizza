package com.jizza.engine.connectors.oauth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/** RFC 7636 PKCE. The verifier stays server-side; only its S256 challenge is ever sent. */
public final class Pkce {

  private Pkce() {}

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final Base64.Encoder URL64 = Base64.getUrlEncoder().withoutPadding();

  public static String newVerifier() {
    byte[] bytes = new byte[64];
    RANDOM.nextBytes(bytes);
    return URL64.encodeToString(bytes);
  }

  public static String challenge(String verifier) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest(verifier.getBytes(StandardCharsets.US_ASCII));
      return URL64.encodeToString(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  public static String newState() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return URL64.encodeToString(bytes);
  }
}
