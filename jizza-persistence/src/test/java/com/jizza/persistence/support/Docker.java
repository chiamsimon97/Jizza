package com.jizza.persistence.support;

/**
 * Integration tests need a real PostgreSQL with pgvector; there is no faithful in-memory stand-in
 * for {@code citext}, {@code inet}, {@code jsonb} and {@code vector}. When no Docker daemon is
 * reachable those tests skip rather than pretend to pass.
 */
public final class Docker {

  private Docker() {}

  public static boolean available() {
    try {
      return org.testcontainers.DockerClientFactory.instance().isDockerAvailable();
    } catch (Throwable t) {
      return false;
    }
  }
}
