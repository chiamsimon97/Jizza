package com.jizza.engine.security.throttle;

import com.jizza.engine.api.error.RateLimitedException;
import com.jizza.engine.security.config.SecurityProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Sliding-window rate limit on the auth endpoints (R6). Keyed by client address <em>and</em> by
 * account, so neither a single host nor a distributed attempt against one account gets free rein.
 */
@Component
public class LoginThrottle {

  private static final Duration WINDOW = Duration.ofMinutes(1);

  private final SecurityProperties props;
  private final Map<String, Deque<Instant>> hits = new ConcurrentHashMap<>();

  public LoginThrottle(SecurityProperties props) {
    this.props = props;
  }

  /** @throws RateLimitedException when the caller has exceeded the configured rate. */
  public void check(String key) {
    Instant now = Instant.now();
    Instant cutoff = now.minus(WINDOW);
    Deque<Instant> window = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
    synchronized (window) {
      while (!window.isEmpty() && window.peekFirst().isBefore(cutoff)) window.pollFirst();
      if (window.size() >= props.getLockout().getAuthRequestsPerMinute()) {
        throw new RateLimitedException("Too many authentication attempts; wait a minute and retry");
      }
      window.addLast(now);
    }
  }

  public void reset(String key) {
    hits.remove(key);
  }

  /** Housekeeping so a long-running process does not accumulate idle keys. */
  public void sweep() {
    Instant cutoff = Instant.now().minus(WINDOW);
    hits.forEach((key, window) -> {
      synchronized (window) {
        while (!window.isEmpty() && window.peekFirst().isBefore(cutoff)) window.pollFirst();
      }
    });
    hits.entrySet().removeIf(e -> {
      synchronized (e.getValue()) {
        return e.getValue().isEmpty();
      }
    });
  }
}
