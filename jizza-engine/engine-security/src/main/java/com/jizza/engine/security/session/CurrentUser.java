package com.jizza.engine.security.session;

import com.jizza.engine.api.error.AuthException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** Reads the authenticated principal off the security context. */
public final class CurrentUser {

  private CurrentUser() {}

  public static Optional<JizzaPrincipal> principal() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth != null && auth.getPrincipal() instanceof JizzaPrincipal p) return Optional.of(p);
    return Optional.empty();
  }

  public static JizzaPrincipal require() {
    return principal().orElseThrow(() -> new AuthException("Not authenticated"));
  }

  public static UUID userId() { return require().userId(); }

  public static UUID sessionId() { return require().sessionId(); }
}
