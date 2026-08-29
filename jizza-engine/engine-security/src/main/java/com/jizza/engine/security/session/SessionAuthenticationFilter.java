package com.jizza.engine.security.session;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Turns the session cookie into an authenticated principal. Every request re-validates the session
 * against the database, so "log out everywhere" takes effect on the very next call.
 */
@Component
public class SessionAuthenticationFilter extends OncePerRequestFilter {

  private final SessionCookies cookies;
  private final SessionService sessions;

  public SessionAuthenticationFilter(SessionCookies cookies, SessionService sessions) {
    this.cookies = cookies;
    this.sessions = sessions;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                  FilterChain chain) throws ServletException, IOException {
    if (SecurityContextHolder.getContext().getAuthentication() == null) {
      cookies.read(request)
          .flatMap(sessions::validateAndTouch)
          .ifPresent(session -> {
            var principal = new JizzaPrincipal(session.getUserId(), session.getId(),
                session.getStepUpAt());
            var auth = new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
            SecurityContextHolder.getContext().setAuthentication(auth);
          });
    }
    chain.doFilter(request, response);
  }
}
