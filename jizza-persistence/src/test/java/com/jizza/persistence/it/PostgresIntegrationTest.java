package com.jizza.persistence.it;

import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base for tests that need the real schema. Uses the {@code pgvector/pgvector} image so
 * {@code V1__init.sql} — extensions and all — runs exactly as it will in production. The container
 * is started lazily from {@code @DynamicPropertySource} so that a disabled subclass never touches
 * Docker at all.
 *
 * <p>Subclasses must repeat the {@code @EnabledIf} guard; JUnit does not inherit it.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(com.jizza.persistence.config.PersistenceConfig.class)
@TestPropertySource(properties = {
    "spring.flyway.enabled=true",
    "spring.jpa.hibernate.ddl-auto=validate"
})
@EnabledIf(value = "com.jizza.persistence.support.Docker#available", disabledReason = "no Docker daemon")
public abstract class PostgresIntegrationTest {

  private static PostgreSQLContainer<?> postgres;

  static synchronized PostgreSQLContainer<?> container() {
    if (postgres == null) {
      postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
          .withDatabaseName("jizza").withUsername("jizza").withPassword("jizza");
      postgres.start();
    }
    return postgres;
  }

  @DynamicPropertySource
  static void datasource(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> container().getJdbcUrl());
    registry.add("spring.datasource.username", () -> container().getUsername());
    registry.add("spring.datasource.password", () -> container().getPassword());
  }
}
