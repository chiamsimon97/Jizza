package com.jizza.engine.security.session;

import com.jizza.engine.security.config.SecurityProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/** httpOnly + Secure + SameSite session cookie handling (R5). */
@Component
public class SessionCookies {

  private final SecurityProperties props;

  public SessionCookies(SecurityProperties props) {
    this.props = props;
  }

  public String cookieName() { return props.getSession().getCookieName(); }

  public ResponseCookie issue(UUID sessionId) {
    return base(sessionId.toString())
        .maxAge(props.getSession().getAbsoluteTimeout())
        .build();
  }

  public ResponseCookie clear() {
    return base("").maxAge(0).build();
  }

  private ResponseCookie.ResponseCookieBuilder base(String value) {
    return ResponseCookie.from(cookieName(), value)
        .httpOnly(true)
        .secure(props.getSession().isCookieSecure())
        .sameSite(props.getSession().getSameSite())
        .path("/");
  }

  public Optional<UUID> read(HttpServletRequest request) {
    if (request.getCookies() == null) return Optional.empty();
    for (var cookie : request.getCookies()) {
      if (cookieName().equals(cookie.getName()) && cookie.getValue() != null
          && !cookie.getValue().isBlank()) {
        try {
          return Optional.of(UUID.fromString(cookie.getValue()));
        } catch (IllegalArgumentException e) {
          return Optional.empty();
        }
      }
    }
    return Optional.empty();
  }
}
