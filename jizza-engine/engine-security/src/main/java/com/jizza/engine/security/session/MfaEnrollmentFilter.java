package com.jizza.engine.security.session;

import com.jizza.engine.security.config.SecurityProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * With two-step sign-in required, a session that has not passed the second factor -- an account
 * that has not set it up yet -- can reach only the auth endpoints (where setup lives). Everything
 * holding the user's data answers 403 {@code mfa-enrollment-required} until setup is finished.
 *
 * <p>Built by {@code SecurityConfig} rather than scanned, so it runs only inside the security chain.
 */
public class MfaEnrollmentFilter extends OncePerRequestFilter {

  public static final String PROBLEM_TYPE = "mfa-enrollment-required";

  private final SecurityProperties props;

  public MfaEnrollmentFilter(SecurityProperties props) {
    this.props = props;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                  FilterChain chain) throws ServletException, IOException {
    if (props.getMfa().isRequired() && !exempt(request)) {
      var principal = CurrentUser.principal();
      if (principal.isPresent() && !principal.get().mfaSatisfied()) {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/problem+json");
        response.getWriter().write("{\"type\":\"" + com.jizza.engine.api.error.JizzaException.TYPE_BASE
            + PROBLEM_TYPE + "\",\"title\":\"Two-step sign-in required\",\"status\":403,"
            + "\"detail\":\"Set up two-step sign-in before using Jizza.\"}");
        return;
      }
    }
    chain.doFilter(request, response);
  }

  private static boolean exempt(HttpServletRequest request) {
    String path = request.getRequestURI().substring(request.getContextPath().length());
    return path.startsWith("/api/auth/") || path.startsWith("/actuator/");
  }
}
