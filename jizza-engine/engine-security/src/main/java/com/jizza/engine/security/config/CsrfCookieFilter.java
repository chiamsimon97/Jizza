package com.jizza.engine.security.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Makes the CSRF cookie actually reach the browser.
 *
 * <p>Spring Security defers loading the token, so the {@code XSRF-TOKEN} cookie is only written if
 * something reads the token during the request. A single-page client never does -- it just wants
 * the cookie -- so without this it would receive nothing to echo back and every mutating request
 * would be rejected. Touching the token here resolves it and triggers the cookie.
 */
public class CsrfCookieFilter extends OncePerRequestFilter {

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                  FilterChain chain) throws ServletException, IOException {
    CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
    if (token != null) token.getToken();
    chain.doFilter(request, response);
  }
}
