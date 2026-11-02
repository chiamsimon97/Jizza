package com.jizza.engine.security.config;

import com.jizza.engine.security.session.MfaEnrollmentFilter;
import com.jizza.engine.security.session.SessionAuthenticationFilter;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Transport and application hardening (R12) plus the session-cookie authentication chain.
 *
 * <p>Spring's own HTTP session is switched off: Jizza owns its sessions in the database so they can
 * be listed, revoked and expired on both idle and absolute clocks.
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(SecurityProperties.class)
public class SecurityConfig {

  /** Endpoints reachable without a session. Everything else requires one. */
  public static final String[] PUBLIC_ENDPOINTS = {
      "/api/auth/csrf", "/api/auth/register", "/api/auth/login", "/api/auth/mfa/verify",
      "/api/auth/recover", "/api/auth/recover/**", "/api/auth/invitations/*",
      // Smart sign-in: the login page asks for a QR code and waits for a phone to approve it.
      "/api/auth/smart-sign-in/config", "/api/auth/smart-sign-in/start",
      "/api/auth/smart-sign-in/attempts/**",
      "/api/oauth/callback", "/api/mcp/oauth/callback", "/api/webhooks/**",
      // Paired devices sign in with their own key and short-lived token, never a session.
      "/agent/v1/**",
      "/actuator/health", "/actuator/health/**", "/actuator/info"
  };

  /**
   * Argon2id, the Charter's choice. Parameters follow Spring Security's current defaults
   * (m=16MiB, t=2, p=1), which are the OWASP-recommended settings for interactive login.
   */
  @Bean
  public PasswordEncoder passwordEncoder() {
    return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
  }

  @Bean
  public SecurityFilterChain filterChain(HttpSecurity http, SessionAuthenticationFilter sessionFilter,
                                         SecurityProperties props) throws Exception {
    var csrfHandler = new CsrfTokenRequestAttributeHandler();
    // Opt out of deferred CSRF token loading. Deferred loading means the token is only resolved if
    // something reads it during the request, which leaves a single-page client with no token to
    // echo back -- and, worse, lets a request that clears the cookie finish without issuing a
    // replacement. Eager loading plus CsrfCookieFilter is Spring's documented SPA arrangement.
    csrfHandler.setCsrfRequestAttributeName(null);

    http
        .cors(cors -> cors.configurationSource(corsSource(props)))
        .csrf(csrf -> csrf
            .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
            .csrfTokenRequestHandler(csrfHandler)
            // The OAuth provider, third-party webhook senders and device apps cannot carry our
            // CSRF token; each is authenticated by its own secret or signed token instead.
            .ignoringRequestMatchers(
                new AntPathRequestMatcher("/agent/v1/**"),
                new AntPathRequestMatcher("/api/oauth/callback"),
                new AntPathRequestMatcher("/api/mcp/oauth/callback"),
                new AntPathRequestMatcher("/api/webhooks/**")))
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .headers(headers -> headers
            .httpStrictTransportSecurity(hsts -> hsts
                .includeSubDomains(true)
                .maxAgeInSeconds(31_536_000))
            .frameOptions(frame -> frame.deny())
            .contentSecurityPolicy(csp -> csp.policyDirectives(
                "default-src 'self'; frame-ancestors 'none'; object-src 'none'; "
                    + "base-uri 'self'; form-action 'self'"))
            .referrerPolicy(referrer -> referrer.policy(
                org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter
                    .ReferrerPolicy.SAME_ORIGIN))
            // Only the microphone is used (voice input). Nothing else may be reached, even by
            // injected script.
            .permissionsPolicyHeader(permissions -> permissions.policy(
                "camera=(), microphone=(self), geolocation=(), payment=(), usb=(), "
                    + "interest-cohort=()"))
            .crossOriginOpenerPolicy(coop -> coop.policy(
                org.springframework.security.web.header.writers.CrossOriginOpenerPolicyHeaderWriter
                    .CrossOriginOpenerPolicy.SAME_ORIGIN))
            .crossOriginResourcePolicy(corp -> corp.policy(
                org.springframework.security.web.header.writers.CrossOriginResourcePolicyHeaderWriter
                    .CrossOriginResourcePolicy.SAME_ORIGIN)))
        .authorizeHttpRequests(auth -> auth
            .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
            .requestMatchers(PUBLIC_ENDPOINTS).permitAll()
            .anyRequest().authenticated())
        .httpBasic(basic -> basic.disable())
        .formLogin(form -> form.disable())
        .logout(logout -> logout.disable())
        .exceptionHandling(ex -> ex
            .authenticationEntryPoint(problemEntryPoint())
            .accessDeniedHandler((request, response, denied) ->
                writeProblem(response, 403, "forbidden", "Forbidden", denied.getMessage())))
        .addFilterBefore(sessionFilter, UsernamePasswordAuthenticationFilter.class)
        .addFilterAfter(new MfaEnrollmentFilter(props), SessionAuthenticationFilter.class)
        .addFilterAfter(new CsrfCookieFilter(), org.springframework.security.web.csrf.CsrfFilter.class);

    return http.build();
  }

  /** Unauthenticated calls get an RFC 9457 body like every other error, not an HTML login page. */
  private org.springframework.security.web.AuthenticationEntryPoint problemEntryPoint() {
    return (request, response, authException) -> writeProblem(response, 401, "authentication",
        "Authentication failed", "A valid session is required for this endpoint");
  }

  private static void writeProblem(jakarta.servlet.http.HttpServletResponse response, int status,
                                   String type, String title, String detail) throws java.io.IOException {
    response.setStatus(status);
    response.setContentType("application/problem+json");
    response.getWriter().write(String.format(
        "{\"type\":\"%s%s\",\"title\":\"%s\",\"status\":%d,\"detail\":\"%s\"}",
        com.jizza.engine.api.error.JizzaException.TYPE_BASE, type, title, status,
        detail == null ? "" : detail.replace("\"", "'")));
  }

  private CorsConfigurationSource corsSource(SecurityProperties props) {
    CorsConfiguration config = new CorsConfiguration();
    config.setAllowedOrigins(props.getCors().getAllowedOrigins());
    config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
    config.setAllowedHeaders(List.of("Content-Type", "X-XSRF-TOKEN", "Accept"));
    config.setAllowCredentials(true);
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", config);
    return source;
  }
}
