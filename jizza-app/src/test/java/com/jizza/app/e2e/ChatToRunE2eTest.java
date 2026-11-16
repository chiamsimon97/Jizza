package com.jizza.app.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.enums.NodeType;
import com.jizza.engine.api.llm.LlmResponse;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.workflow.graph.WorkflowEdge;
import com.jizza.engine.api.workflow.graph.WorkflowGraph;
import com.jizza.engine.api.workflow.graph.WorkflowNode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.junit.jupiter.api.AfterEach;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * A request in plain words runs a saved workflow: one cheap reading of the message, a start card
 * with what it said, Start, and follow-ups in the same chat.
 */
@org.junit.jupiter.api.condition.EnabledIf(
    value = "com.jizza.engine.persistence.support.TestDatabase#available",
    disabledReason = "no PostgreSQL: set JIZZA_TEST_DB_URL or start Docker")
class ChatToRunE2eTest extends E2eSupport {

  private static final String PASSWORD = "Correct-Horse-92";

  @LocalServerPort private int port;
  @Autowired private E2eSupport.ScriptedLlmClient model;
  @Autowired private com.jizza.engine.orchestrator.library.ChatRunPlanner planner;

  private ApiClient api;

  @BeforeEach
  void setUp() {
    api = new ApiClient(port);
    api.prime();
    model.reset();
    // The suite turns it off so it never takes another test's scripted reply; on for these only,
    // in the same shared context.
    ReflectionTestUtils.setField(planner, "enabled", true);
    String email = "chatrun-" + UUID.randomUUID() + "@jizza.local";
    api.post("/api/auth/register", Map.of("email", email, "password", PASSWORD));
    api.post("/api/auth/login", Map.of("email", email, "password", PASSWORD));
  }

  @AfterEach
  void tearDown() {
    ReflectionTestUtils.setField(planner, "enabled", false);
  }

  private UUID trainBooking() {
    WorkflowGraph graph = new WorkflowGraph(
        List.of(new WorkflowNode("start", NodeType.TRIGGER, "Start", Json.read("""
                {"inputs":[{"name":"from","type":"text","required":true,"label":"From"},
                           {"name":"to","type":"text","required":true,"label":"To"},
                           {"name":"travelClass","type":"text","label":"Class",
                            "options":["Sleeper (SL)","AC 3 Tier (3A)"]},
                           {"name":"train","type":"text","required":true,"ask":"atStep"}]}"""), null),
            new WorkflowNode("finish", NodeType.END, "Done", Json.read("""
                {"outputs":[{"name":"route","value":"{{ input.from }} to {{ input.to }}"}]}"""), null)),
        List.of(new WorkflowEdge("start", "finish", null)));
    var saved = api.post("/api/workflows", Map.of("name", "Simon", "graph", graph.toJson()));
    assertThat(saved.ok()).as(saved.body()).isTrue();
    UUID id = UUID.fromString(saved.json().path("id").asText());
    var reuse = api.put("/api/workflows/" + id + "/reuse", Map.of("summary", "Books a train ticket",
        "examples", List.of("book a train ticket", "railway booking")));
    assertThat(reuse.ok()).as(reuse.body()).isTrue();
    return id;
  }

  @Test
  void aPlainRequestGetsAStartCardThenRunsAndAnswersFollowUps() {
    UUID id = trainBooking();
    model.script(LlmResponse.text("""
        {"run": true, "workflow": 1, "values": {"from": "MAS", "to": "NDLS", "travelClass": "3A"}}"""));

    var reply = api.post("/api/chat", Map.of("message", "book a train ticket from Chennai to Delhi in 3A"));

    assertThat(reply.ok()).as(reply.body()).isTrue();
    JsonNode card = reply.json().path("actions").get(0);
    assertThat(card.path("type").asText()).isEqualTo("start_run");
    JsonNode payload = card.path("payload");
    assertThat(payload.path("workflowId").asText()).isEqualTo(id.toString());
    assertThat(payload.path("fields").get(2).path("value").asText()).isEqualTo("AC 3 Tier (3A)");
    assertThat(reply.json().path("text").asText()).contains("Ready to run **Simon**").contains("train");
    // One small call to the cheap model, offered no tools at all.
    assertThat(model.requests).hasSize(1);
    assertThat(model.offeredTools.get(0)).isEmpty();
    assertThat(model.everythingTheModelSaw()).contains("book a train ticket from Chennai to Delhi");

    String conversationId = reply.json().path("conversationId").asText();
    var started = api.post("/api/chat/runs", Map.of("conversationId", conversationId,
        "planId", payload.path("planId").asText(), "workflowId", id.toString(),
        "inputs", Map.of("from", "MAS", "to", "NDLS", "travelClass", "AC 3 Tier (3A)", "train", "")));
    assertThat(started.ok()).as(started.body()).isTrue();
    String runId = started.json().path("id").asText();
    var again = api.post("/api/chat/runs", Map.of("conversationId", conversationId,
        "planId", payload.path("planId").asText(), "workflowId", id.toString(), "inputs", Map.of()));
    assertThat(again.status()).isEqualTo(409);

    // The card remembers its run when the conversation is opened again.
    var messages = api.get("/api/conversations/" + conversationId + "/messages");
    assertThat(messages.body()).contains("\"runId\":\"" + runId + "\"", "Started Simon.");

    for (int i = 0; i < 50 && !"DONE".equals(api.get("/api/runs/" + runId).json().path("status").asText()); i++) {
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    var asked = api.post("/api/chat", Map.of("conversationId", conversationId, "message", "what's the route?"));
    assertThat(asked.ok()).as(asked.body()).isTrue();
    assertThat(asked.json().path("text").asText()).contains("MAS to NDLS");
    var stop = api.post("/api/chat", Map.of("conversationId", conversationId, "message", "stop it"));
    assertThat(stop.json().path("text").asText()).contains("Simon");
    assertThat(model.requests).hasSize(1);
  }

  @Test
  void someoneElsesCardCannotBeStarted() {
    UUID id = trainBooking();
    model.script(LlmResponse.text("{\"run\": true, \"workflow\": 1, \"values\": {}}"));
    var reply = api.post("/api/chat", Map.of("message", "book a train ticket"));
    String planId = reply.json().path("actions").get(0).path("payload").path("planId").asText();
    String conversationId = reply.json().path("conversationId").asText();

    ApiClient other = new ApiClient(port);
    other.prime();
    String email = "other-" + UUID.randomUUID() + "@jizza.local";
    other.post("/api/auth/register", Map.of("email", email, "password", PASSWORD));
    other.post("/api/auth/login", Map.of("email", email, "password", PASSWORD));
    var refused = other.post("/api/chat/runs", Map.of("conversationId", conversationId, "planId", planId,
        "workflowId", id.toString(), "inputs", Map.of("from", "A", "to", "B")));
    assertThat(refused.status()).isEqualTo(404);
  }

  @Test
  void aMessageThatIsNotARunRequestGoesToJizza() {
    trainBooking();
    model.script(LlmResponse.text("{\"run\": false}"), LlmResponse.text("Trains are a fine way to travel."));

    var reply = api.post("/api/chat", Map.of("message", "tell me about train ticket classes"));

    assertThat(reply.ok()).as(reply.body()).isTrue();
    assertThat(reply.json().path("actions").toString()).doesNotContain("start_run");
    assertThat(reply.json().path("text").asText()).contains("fine way to travel");
  }
}
