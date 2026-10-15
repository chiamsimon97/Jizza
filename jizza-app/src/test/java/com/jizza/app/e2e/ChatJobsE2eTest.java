package com.jizza.app.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.llm.LlmResponse;
import com.jizza.engine.api.tool.ToolCall;
import com.jizza.engine.api.util.Json;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Acceptance for the chat workspace: a message starts a job that runs in the background, reports
 * what it does as events the Activity panel can replay, can be stopped, and is safe to retry. The
 * conversation list is a summary with titles, pins and archive.
 */
@org.junit.jupiter.api.condition.EnabledIf(
    value = "com.jizza.engine.persistence.support.TestDatabase#available",
    disabledReason = "no PostgreSQL: set JIZZA_TEST_DB_URL or start Docker")
class ChatJobsE2eTest extends E2eSupport {

  private static final String PASSWORD = "Correct-Horse-92";

  @LocalServerPort private int port;
  @Autowired private E2eSupport.ScriptedLlmClient model;
  @Autowired private JdbcTemplate jdbc;

  private ApiClient api;
  private UUID userId;
  private CountDownLatch release;

  @BeforeEach
  void setUp() {
    api = new ApiClient(port);
    api.prime();
    model.reset();
    release = null;

    String email = "jobs-" + UUID.randomUUID() + "@jizza.local";
    api.post("/api/auth/register", Map.of("email", email, "password", PASSWORD));
    var login = api.post("/api/auth/login", Map.of("email", email, "password", PASSWORD));
    userId = UUID.fromString(login.json().path("userId").asText());
  }

  @AfterEach
  void unblock() {
    if (release != null) release.countDown();
  }

  /** Makes the next model call wait until the test lets it go, so a turn can be caught mid-flight. */
  private void holdTheModel(String reply) {
    CountDownLatch latch = new CountDownLatch(1);
    release = latch;
    model.fallback = messages -> {
      try {
        latch.await(20, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      return LlmResponse.text(reply);
    };
  }

  private Map<String, Object> turn(String text, UUID conversationId) {
    var body = new java.util.HashMap<String, Object>();
    body.put("message", text);
    body.put("clientMessageId", UUID.randomUUID().toString());
    if (conversationId != null) body.put("conversationId", conversationId.toString());
    return body;
  }

  private JsonNode awaitJob(String jobId, String status) {
    Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
    JsonNode job = null;
    while (Instant.now().isBefore(deadline)) {
      job = api.get("/api/chat/jobs/" + jobId).json();
      if (status.equals(job.path("status").asText())) return job;
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    throw new AssertionError("job " + jobId + " never reached " + status + ": " + job);
  }

  private static List<String> types(JsonNode job) {
    List<String> out = new ArrayList<>();
    job.path("events").forEach(e -> out.add(e.path("type").asText()));
    return out;
  }

  @Test
  void aMessageStartsAJobWhoseEventsTellWhoDidWhat() {
    model.scriptFor("Jizza",
        new LlmResponse("", List.of(new ToolCall("call-kai", "delegate_to_specialist",
            Json.obj().put("agent_key", "finance").put("task", "what is in my investment plan?"))),
            "tool_use", 10, 5, "m"),
        LlmResponse.text("Kai says your plan is 60% index funds."));
    model.scriptFor("Kai", LlmResponse.text("60% index funds, 40% bonds."));

    var started = api.post("/api/chat/turns", turn("How is my investment plan split?", null));
    assertThat(started.status()).as(started.body()).isEqualTo(202);
    String jobId = started.json().path("jobId").asText();
    String conversationId = started.json().path("conversationId").asText();

    JsonNode job = awaitJob(jobId, "completed");
    assertThat(types(job)).startsWith("job.started").endsWith("job.completed")
        .contains("agent.message").doesNotContain("delta");
    List<JsonNode> exchange = new ArrayList<>();
    job.path("events").forEach(e -> { if (e.path("type").asText().equals("agent.message")) exchange.add(e); });
    assertThat(exchange).hasSize(2);
    assertThat(exchange.get(0).path("agentName").asText()).isEqualTo("Jizza");
    assertThat(exchange.get(0).path("payload").path("toAgentName").asText()).isEqualTo("Kai");
    assertThat(exchange.get(1).path("payload").path("text").asText()).contains("60% index funds");
    assertThat(job.path("summary").asText()).contains("Asked Kai");

    // The conversation shows only Jizza's voice, finished, linked to the job that produced it.
    var messages = api.get("/api/conversations/" + conversationId + "/messages").json();
    assertThat(messages).hasSize(2);
    JsonNode reply = messages.get(1);
    assertThat(reply.path("role").asText()).isEqualTo("assistant");
    assertThat(reply.path("content").asText()).isEqualTo("Kai says your plan is 60% index funds.");
    assertThat(reply.path("status").asText()).isEqualTo("complete");
    assertThat(reply.path("jobId").asText()).isEqualTo(jobId);

    var jobs = api.get("/api/conversations/" + conversationId + "/jobs").json();
    assertThat(jobs).hasSize(1);
    assertThat(jobs.get(0).path("id").asText()).isEqualTo(jobId);

    // Catch-up after a gap returns only what came later.
    int lastSeq = job.path("events").get(job.path("events").size() - 1).path("seq").asInt();
    assertThat(api.get("/api/chat/jobs/" + jobId + "?afterSeq=" + (lastSeq - 1)).json()
        .path("events")).hasSize(1);
  }

  @Test
  void sendingWithoutAConversationStartsANewOneEachTime() {
    model.scriptFor("Jizza", LlmResponse.text("one"), LlmResponse.text("two"));
    var first = api.post("/api/chat", Map.of("message", "first"));
    var second = api.post("/api/chat", Map.of("message", "second"));
    assertThat(first.ok()).as(first.body()).isTrue();
    assertThat(first.json().path("conversationId").asText())
        .isNotEqualTo(second.json().path("conversationId").asText());
  }

  @Test
  void aRetriedSendIsTheSameTurnNotASecondOne() {
    model.scriptFor("Jizza", LlmResponse.text("hello back"));
    Map<String, Object> body = turn("hello", null);

    var first = api.post("/api/chat/turns", body);
    String jobId = first.json().path("jobId").asText();
    awaitJob(jobId, "completed");

    body.put("conversationId", first.json().path("conversationId").asText());
    var retry = api.post("/api/chat/turns", body);
    assertThat(retry.json().path("jobId").asText()).isEqualTo(jobId);
    assertThat(api.get("/api/conversations/" + first.json().path("conversationId").asText()
        + "/messages").json()).hasSize(2);
  }

  @Test
  void oneReplyAtATimeAndStopEndsItKeepingWhatWasSaid() {
    holdTheModel("This answer arrives after the stop.");
    var first = api.post("/api/chat/turns", turn("Plan my week", null));
    String jobId = first.json().path("jobId").asText();
    UUID conversationId = UUID.fromString(first.json().path("conversationId").asText());

    var second = api.post("/api/chat/turns", turn("And next week?", conversationId));
    assertThat(second.status()).as(second.body()).isEqualTo(409);

    assertThat(api.post("/api/chat/jobs/" + jobId + "/cancel", Map.of()).status()).isEqualTo(202);
    release.countDown();

    JsonNode job = awaitJob(jobId, "cancelled");
    assertThat(types(job)).contains("job.cancelled").doesNotContain("job.completed");
    var reply = api.get("/api/conversations/" + conversationId + "/messages").json().get(1);
    assertThat(reply.path("status").asText()).isEqualTo("cancelled");

    // Once stopped, the conversation takes the next message.
    model.fallback = messages -> LlmResponse.text("ok");
    var third = api.post("/api/chat/turns", turn("Try again", conversationId));
    assertThat(third.status()).as(third.body()).isEqualTo(202);
    awaitJob(third.json().path("jobId").asText(), "completed");
  }

  @Test
  void anIncognitoTurnIsFollowableButLeavesNothingBehind() {
    model.scriptFor("Jizza", LlmResponse.text("Not saved."));
    var body = turn("a private question", null);
    body.put("incognito", true);

    var started = api.post("/api/chat/turns", body);
    assertThat(started.status()).as(started.body()).isEqualTo(202);
    assertThat(started.json().path("conversationId").isNull()
        || started.json().path("conversationId").isMissingNode()).isTrue();
    JsonNode job = awaitJob(started.json().path("jobId").asText(), "completed");
    assertThat(types(job)).contains("job.completed");

    assertThat(jdbc.queryForObject("select count(*) from conversations where user_id = ?",
        Integer.class, userId)).isZero();
    assertThat(jdbc.queryForObject("select count(*) from jobs where user_id = ?",
        Integer.class, userId)).isZero();
  }

  @Test
  void theConversationListIsTitledAndCanBePinnedRenamedArchivedAndDeleted() {
    model.scriptFor("Jizza", LlmResponse.text("a"), LlmResponse.text("b"));
    String older = api.post("/api/chat", Map.of("message", "Budget for the wedding in March"))
        .json().path("conversationId").asText();
    String newer = api.post("/api/chat", Map.of("message", "Book a dentist appointment"))
        .json().path("conversationId").asText();

    var list = api.get("/api/conversations").json();
    assertThat(list.findValuesAsText("id")).containsExactly(newer, older);
    assertThat(list.get(1).path("title").asText()).isEqualTo("Budget for the wedding in March");
    assertThat(list.get(1).path("messageCount").asInt()).isEqualTo(2);

    api.patch("/api/conversations/" + older, Map.of("pinned", true, "title", "Wedding budget"));
    list = api.get("/api/conversations").json();
    assertThat(list.findValuesAsText("id")).containsExactly(older, newer);
    assertThat(list.get(0).path("title").asText()).isEqualTo("Wedding budget");

    assertThat(api.get("/api/conversations?q=dentist").json().findValuesAsText("id"))
        .containsExactly(newer);

    api.patch("/api/conversations/" + newer, Map.of("archived", true));
    assertThat(api.get("/api/conversations").json().findValuesAsText("id")).containsExactly(older);
    assertThat(api.get("/api/conversations?archived=true").json().findValuesAsText("id"))
        .containsExactly(newer);

    assertThat(api.delete("/api/conversations/" + older).status()).isEqualTo(204);
    assertThat(api.get("/api/conversations/" + older).status()).isEqualTo(404);
  }
}
