package com.jizza.engine.devices.registry;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** A sliding one-minute window per key, for enrollment and sign-in attempts from one address. */
public class AttemptLimiter {

  private final int perMinute;
  private final Clock clock;
  private final Map<String, Deque<Instant>> attempts = new ConcurrentHashMap<>();

  public AttemptLimiter(int perMinute, Clock clock) {
    this.perMinute = perMinute;
    this.clock = clock;
  }

  /** Records an attempt; false when the key has used up its minute. */
  public boolean tryAcquire(String key) {
    if (key == null) key = "unknown";
    Instant now = clock.instant();
    Deque<Instant> window = attempts.computeIfAbsent(key, k -> new ArrayDeque<>());
    synchronized (window) {
      while (!window.isEmpty() && window.peekFirst().isBefore(now.minusSeconds(60))) {
        window.pollFirst();
      }
      if (window.size() >= perMinute) return false;
      window.addLast(now);
      return true;
    }
  }

  public void clear() {
    attempts.clear();
  }
}
