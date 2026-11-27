package com.jizza.app.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.jizza.platform.usecase.vita.tools.VitaToolProvider;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** With {@code jizza.health.enabled} off, Vita's API does not exist and Vita has no health tools. */
@org.junit.jupiter.api.condition.EnabledIf(
    value = "com.jizza.engine.persistence.support.TestDatabase#available",
    disabledReason = "no PostgreSQL: set JIZZA_TEST_DB_URL or start Docker")
class VitaSwitchedOffE2eTest extends E2eSupport {

  @DynamicPropertySource
  static void healthOff(DynamicPropertyRegistry registry) {
    registry.add("e2e.health-enabled", () -> "false");
  }

  @LocalServerPort private int port;
  @Autowired private org.springframework.context.ApplicationContext context;
  @Autowired private VitaToolProvider tools;

  @Test
  void theHealthApiAnswers404AndVitaHasNoHealthTools() {
    ApiClient api = new ApiClient(port);
    api.prime();
    String email = "vita-off-" + UUID.randomUUID() + "@jizza.local";
    assertThat(api.post("/api/auth/register", Map.of("email", email, "password", "Correct-Horse-92")).ok()).isTrue();
    assertThat(api.post("/api/auth/login", Map.of("email", email, "password", "Correct-Horse-92")).ok()).isTrue();

    assertThat(api.get("/api/vita/status").status()).isEqualTo(404);
    assertThat(context.getBeansOfType(com.jizza.platform.api.jobs.VitaJobs.class))
        .as("neither Vita job runs while the switch is off").isEmpty();
    assertThat(api.get("/api/vita/config").status()).isEqualTo(404);
    assertThat(api.get("/api/people").ok()).as("the shared people list does not depend on Vita").isTrue();

    for (var agent : api.get("/api/agents").json()) {
      UUID id = UUID.fromString(agent.path("id").asText());
      assertThat(tools.toolsFor(id)).as("tools for %s", agent.path("key").asText()).isEmpty();
      assertThat(tools.supports(id, VitaToolProvider.OVERVIEW)).isFalse();
    }
  }
}
