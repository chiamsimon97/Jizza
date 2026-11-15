package com.jizza.platform.api.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Refuses device-agent traffic that did not arrive over TLS (plan 05, P0). Device agents carry
 * field values for web automation; those must never cross a network readable to others. The
 * production extension build already refuses to pair over plain HTTP; this makes the server
 * refuse too, so a misconfigured client cannot talk it into the clear.
 *
 * <p>Off by default so local development (the backend on a LAN address, no certificate) keeps
 * working; turn it on with {@code jizza.security.require-https=true} wherever real data flows.
 * Loopback is always allowed, the same exception the extension's vault makes. A reverse proxy
 * that terminates TLS says so with {@code X-Forwarded-Proto: https}.
 */
@Component
@ConditionalOnProperty(value = "jizza.security.require-https", havingValue = "true")
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequireHttpsFilter extends OncePerRequestFilter {

  private static final Set<String> LOOPBACK = Set.of("127.0.0.1", "0:0:0:0:0:0:0:1", "::1");

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI().substring(request.getContextPath().length());
    return !path.startsWith("/agent/");
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                  FilterChain chain) throws ServletException, IOException {
    if (secure(request)) {
      chain.doFilter(request, response);
      return;
    }
    response.setStatus(426); // Upgrade Required
    response.setContentType("application/json");
    response.getWriter().write(
        "{\"error\":\"https_required\",\"message\":\"Device agents must connect over HTTPS\"}");
  }

  private boolean secure(HttpServletRequest request) {
    if (request.isSecure()) return true;
    String forwarded = request.getHeader("X-Forwarded-Proto");
    if (forwarded != null && forwarded.toLowerCase(Locale.ROOT).startsWith("https")) return true;
    return LOOPBACK.contains(request.getRemoteAddr());
  }
}
