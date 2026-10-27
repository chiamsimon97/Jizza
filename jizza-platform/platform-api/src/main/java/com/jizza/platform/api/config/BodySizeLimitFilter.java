package com.jizza.platform.api.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Refuses a non-file request body over a limit before anything reads it.
 *
 * <p>File uploads have their own multipart limit. Everything else on this API is JSON a person
 * typed or a workflow graph, which are far smaller; without a cap a single request could make the
 * server parse, validate and hold tens of megabytes. The answer is the same problem shape as every
 * other error.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class BodySizeLimitFilter extends OncePerRequestFilter {

  private final long maxBytes;

  public BodySizeLimitFilter(@Value("${jizza.web.max-body-bytes:2097152}") long maxBytes) {
    this.maxBytes = maxBytes;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    // Device apps upload result files as raw bodies; that route enforces its own limit.
    String path = request.getRequestURI().substring(request.getContextPath().length());
    if (path.equals("/agent/v1/files")) return true;
    String type = request.getContentType();
    return type != null && type.toLowerCase(java.util.Locale.ROOT).startsWith("multipart/");
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                  FilterChain chain) throws ServletException, IOException {
    if (request.getContentLengthLong() > maxBytes) {
      response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
      response.setContentType("application/problem+json");
      response.getWriter().write("{\"type\":\"https://jizza.local/problems/validation\","
          + "\"title\":\"Request too large\",\"status\":413,\"detail\":\"That request is too large."
          + " The limit is " + (maxBytes / 1024) + " KB.\"}");
      return;
    }
    chain.doFilter(request, response);
  }
}
