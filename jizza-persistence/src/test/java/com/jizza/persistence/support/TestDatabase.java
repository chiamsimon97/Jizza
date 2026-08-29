package com.jizza.persistence.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Resolves a real PostgreSQL for the database-backed tests.
 *
 * <p>There is no honest in-memory stand-in for {@code citext}, {@code inet}, {@code jsonb} and
 * {@code vector}, so these tests use the real thing or skip. Two ways to supply it:
 *
 * <ul>
 *   <li>set {@code JIZZA_TEST_DB_URL} (with {@code JIZZA_TEST_DB_USER} /
 *       {@code JIZZA_TEST_DB_PASSWORD}) to point at a PostgreSQL that has the {@code pgcrypto},
 *       {@code citext} and {@code vector} extensions available;</li>
 *   <li>or have a Docker daemon, in which case the {@code pgvector/pgvector:pg16} image is started
 *       automatically.</li>
 * </ul>
 *
 * <p>With neither, the guarded tests report as skipped rather than passing without having run.
 */
public final class TestDatabase {

  private TestDatabase() {}

  public static final String URL_PROPERTY = "JIZZA_TEST_DB_URL";

  private static PostgreSQLContainer<?> container;

  public static String externalUrl() {
    String url = System.getenv(URL_PROPERTY);
    if (url == null || url.isBlank()) url = System.getProperty("jizza.test.db.url");
    return url == null || url.isBlank() ? null : url;
  }

  /** Whether the guarded tests can run at all. Referenced by {@code @EnabledIf}. */
  public static boolean available() {
    if (externalUrl() != null) return true;
    try {
      return org.testcontainers.DockerClientFactory.instance().isDockerAvailable();
    } catch (Throwable t) {
      return false;
    }
  }

  public static void apply(DynamicPropertyRegistry registry) {
    String external = externalUrl();
    if (external != null) {
      registry.add("spring.datasource.url", () -> external);
      registry.add("spring.datasource.username",
          () -> setting("JIZZA_TEST_DB_USER", "jizza.test.db.user", "jizza"));
      registry.add("spring.datasource.password",
          () -> setting("JIZZA_TEST_DB_PASSWORD", "jizza.test.db.password", "jizza"));
      return;
    }
    registry.add("spring.datasource.url", () -> container().getJdbcUrl());
    registry.add("spring.datasource.username", () -> container().getUsername());
    registry.add("spring.datasource.password", () -> container().getPassword());
  }

  private static String setting(String env, String property, String fallback) {
    String value = System.getenv(env);
    if (value == null || value.isBlank()) value = System.getProperty(property);
    return value == null || value.isBlank() ? fallback : value;
  }

  /** Started once per JVM and shared; Testcontainers reaps it when the JVM exits. */
  public static synchronized PostgreSQLContainer<?> container() {
    if (container == null) {
      container = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
          .withDatabaseName("jizza").withUsername("jizza").withPassword("jizza");
      container.start();
    }
    return container;
  }
}
