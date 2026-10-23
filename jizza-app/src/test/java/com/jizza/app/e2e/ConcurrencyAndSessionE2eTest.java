package com.jizza.app.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.jizza.engine.api.llm.LlmResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * What happens when a person double-clicks, opens two tabs, or comes back after their session ran
 * out: simultaneous calls on the same thing are settled cleanly (one wins, the rest get a 4xx
 * answer, nothing is half-written), and an expired session is a clean 401 the client can act on.
 */
@org.junit.jupiter.api.condition.EnabledIf(
    value = "com.jizza.engine.persistence.support.TestDatabase#available",
    disabledReason = "no PostgreSQL: set JIZZA_TEST_DB_URL or start Docker")
class ConcurrencyAndSessionE2eTest extends E2eSupport {

  private static final String PASSWORD = "Correct-Horse-92";
  private static final int CALLERS = 8;

  @LocalServerPort private int port;
  @Autowired private E2eSupport.ScriptedLlmClient model;
  @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;

  private ApiClient api;
  private String email;
  private CountDownLatch release;
  private ExecutorService pool;

  @BeforeEach
  void setUp() {
    model.reset();
    pool = Executors.newFixedThreadPool(CALLERS);
    email = "concurrent-" + UUID.randomUUID() + "@jizza.local";
    api = new ApiClient(port);
    api.prime();
    assertThat(api.post("/api/auth/register", Map.of("email", email, "password", PASSWORD)).ok())
        .isTrue();
    assertThat(api.post("/api/auth/login", Map.of("email", email, "password", PASSWORD)).ok())
        .isTrue();
  }

  @AfterEach
  void tearDown() {
    if (release != null) release.countDown();
    pool.shutdownNow();
  }

  /** Runs {@code call} from {@link #CALLERS} threads released at the same instant. */
  private List<ApiClient.Result> together(Callable<ApiClient.Result> call) throws Exception {
    CountDownLatch start = new CountDownLatch(1);
    List<Future<ApiClient.Result>> futures = new ArrayList<>();
    for (int i = 0; i < CALLERS; i++) {
      futures.add(pool.submit(() -> {
        start.await();
        return call.call();
      }));
    }
    start.countDown();
    List<ApiClient.Result> results = new ArrayList<>();
    for (var f : futures) results.add(f.get(60, TimeUnit.SECONDS));
    return results;
  }

  private static long count(List<ApiClient.Result> results, int status) {
    return results.stream().filter(r -> r.status() == status).count();
  }

  @Test
  void simultaneousMessagesToOneConversationStartExactlyOneReply() throws Exception {
    var conversation = api.post("/api/conversations", Map.of("title", "Busy"));
    String conversationId = conversation.json().path("id").asText();

    CountDownLatch latch = new CountDownLatch(1);
    release = latch;
    model.fallback = messages -> {
      try {
        latch.await(20, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      return LlmResponse.text("done");
    };

    var results = together(() -> api.post("/api/chat/turns", Map.of(
        "message", "Are you there?", "conversationId", conversationId,
        "clientMessageId", UUID.randomUUID().toString())));

    assertThat(results).allSatisfy(r -> assertThat(r.status())
        .as("%s %s", r.status(), r.body()).isIn(202, 409));
    assertThat(count(results, 202)).as("replies started").isEqualTo(1);

    latch.countDown();
    // The conversation holds one question and one answer, not eight half-started turns.
    long userMessages = jdbc.queryForObject(
        "select count(*) from messages where conversation_id = ?::uuid and role = 'user'",
        Long.class, conversationId);
    assertThat(userMessages).isEqualTo(1);
  }

  @Test
  void theSameEmailRegisteredTwiceAtOnceMakesOneAccount() throws Exception {
    String contested = "race-" + UUID.randomUUID() + "@jizza.local";
    var results = together(() -> {
      ApiClient client = new ApiClient(port);
      client.prime();
      return client.post("/api/auth/register", Map.of("email", contested, "password", PASSWORD));
    });
    assertThat(results).allSatisfy(r -> assertThat(r.status())
        .as("%s %s", r.status(), r.body()).isLessThan(500));
    long accounts = jdbc.queryForObject(
        "select count(*) from users where lower(email) = lower(?)", Long.class, contested);
    assertThat(accounts).isEqualTo(1);
  }

  @Test
  void simultaneousEditsToOneConversationAllSettle() throws Exception {
    var conversation = api.post("/api/conversations", Map.of("title", "Draft"));
    String id = conversation.json().path("id").asText();
    var results = together(() -> api.patch("/api/conversations/" + id,
        Map.of("title", "Renamed " + Thread.currentThread().getId(), "pinned", true)));
    assertThat(results).allSatisfy(r -> assertThat(r.status())
        .as("%s %s", r.status(), r.body()).isLessThan(500));
    assertThat(results.stream().filter(ApiClient.Result::ok).count()).isGreaterThanOrEqualTo(1);
    assertThat(jdbc.queryForObject("select title from conversations where id = ?::uuid",
        String.class, id)).startsWith("Renamed");
  }

  @Test
  void deletingTheSameThingTwiceAtOnceIsNotAServerError() throws Exception {
    var conversation = api.post("/api/conversations", Map.of("title", "Doomed"));
    String id = conversation.json().path("id").asText();
    var results = together(() -> api.delete("/api/conversations/" + id));
    assertThat(results).allSatisfy(r -> assertThat(r.status())
        .as("%s %s", r.status(), r.body()).isLessThan(500));
    assertThat(results.stream().filter(ApiClient.Result::ok).count()).isGreaterThanOrEqualTo(1);
    assertThat(api.get("/api/conversations/" + id).status()).isEqualTo(404);
  }

  @Test
  void anIdleExpiredSessionIsACleanUnauthorized() {
    assertThat(api.get("/api/agents").ok()).isTrue();
    jdbc.update("update sessions set idle_expires_at = now() - interval '1 minute' "
        + "where user_id = (select id from users where lower(email) = lower(?))", email);

    var expired = api.get("/api/agents");
    assertThat(expired.status()).isEqualTo(401);
    assertThat(expired.json().path("type").asText()).isNotBlank();
    // A write is refused the same way, and changes nothing.
    var write = api.post("/api/conversations", Map.of("title", "After expiry"));
    assertThat(write.status()).isIn(401, 403);

    // Signing in again works straight away.
    assertThat(api.post("/api/auth/login", Map.of("email", email, "password", PASSWORD)).ok())
        .isTrue();
    assertThat(api.get("/api/agents").ok()).isTrue();
    assertThat(api.get("/api/conversations").body()).doesNotContain("After expiry");
  }

  @Test
  void anAbsolutelyExpiredSessionIsRefusedEvenWhileActive() {
    jdbc.update("update sessions set absolute_expires_at = now() - interval '1 second' "
        + "where user_id = (select id from users where lower(email) = lower(?))", email);
    assertThat(api.get("/api/agents").status()).isEqualTo(401);
  }

  @Test
  void aStaleCsrfTokenIsRefusedAndAFreshOneWorks() {
    var stale = api.sendWithoutCsrf("POST", "/api/conversations", Map.of("title", "Forged"));
    assertThat(stale.status()).isEqualTo(403);
    var fresh = api.post("/api/conversations", Map.of("title", "Real"));
    assertThat(fresh.ok()).as(fresh.body()).isTrue();
  }
}
