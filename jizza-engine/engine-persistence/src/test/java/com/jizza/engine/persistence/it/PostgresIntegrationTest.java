package com.jizza.engine.persistence.it;

import com.jizza.engine.persistence.support.TestDatabase;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * Base for tests that need the real schema, so {@code V1__init.sql} runs exactly as it will in
 * production, extensions and all.
 *
 * <p>Subclasses must repeat the {@code @EnabledIf} guard; JUnit does not inherit it.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(com.jizza.engine.persistence.config.PersistenceConfig.class)
@TestPropertySource(properties = {
    "spring.flyway.enabled=true",
    "spring.jpa.hibernate.ddl-auto=validate"
})
@EnabledIf(value = "com.jizza.engine.persistence.support.TestDatabase#available",
    disabledReason = "no PostgreSQL: set JIZZA_TEST_DB_URL or start Docker")
public abstract class PostgresIntegrationTest {

  @DynamicPropertySource
  static void datasource(DynamicPropertyRegistry registry) {
    TestDatabase.apply(registry);
  }
}
