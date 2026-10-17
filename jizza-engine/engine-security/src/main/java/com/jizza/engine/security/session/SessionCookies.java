package com.jizza.engine.security.session;

import com.jizza.engine.security.config.SecurityProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * httpOnly + Secure + SameSite session cookie handling (R5).
 *
 * <p>The cookie carries an opaque random token, never the session's row id. When the cookie is
 * Secure it also takes the {@code __Host-} prefix, which browsers only accept from a secure origin,
 * on path {@code /} and without a Domain, so a sibling subdomain cannot plant or overwrite it.
 */
@Component
public class SessionCookies {

  private static final int MAX_TOKEN_LENGTH = 128;

  private final SecurityProperties props;

  public SessionCookies(SecurityProperties props) {
    this.props = props;
  }

  public String cookieName() {
    String name = props.getSession().getCookieName();
    return props.getSession().isCookieSecure() ? "__Host-" + name : name;
  }

  public ResponseCookie issue(String token) {
    return base(token)
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

  public Optional<String> read(HttpServletRequest request) {
    if (request.getCookies() == null) return Optional.empty();
    for (var cookie : request.getCookies()) {
      if (cookieName().equals(cookie.getName())) {
        String value = cookie.getValue();
        if (value == null || value.isBlank() || value.length() > MAX_TOKEN_LENGTH) {
          return Optional.empty();
        }
        return Optional.of(value);
      }
    }
    return Optional.empty();
  }
}
