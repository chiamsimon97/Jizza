package com.jizza.app.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.enums.NodeType;
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

/** {@code /run <workflow>} in chat runs the workflow without asking the model anything. */
class RunCommandE2eTest extends E2eSupport {

  private static final String PASSWORD = "Correct-Horse-92";

  @LocalServerPort private int port;
  @Autowired private E2eSupport.ScriptedLlmClient model;

  private ApiClient api;

  @BeforeEach
  void setUp() {
    api = new ApiClient(port);
    api.prime();
    model.reset();
    String email = "run-" + UUID.randomUUID() + "@jizza.local";
    api.post("/api/auth/register", Map.of("email", email, "password", PASSWORD));
    api.post("/api/auth/login", Map.of("email", email, "password", PASSWORD));
  }

  private UUID workflow(String name) {
    WorkflowGraph graph = new WorkflowGraph(
        List.of(new WorkflowNode("start", NodeType.TRIGGER, "start", null, null),
            new WorkflowNode("double", NodeType.TRANSFORM, "double", Json.obj().put("expression", "2 * 2"), null),
            new WorkflowNode("finish", NodeType.END, "finish", null, null)),
        List.of(new WorkflowEdge("start", "double", null), new WorkflowEdge("double", "finish", null)));
    var saved = api.post("/api/workflows", Map.of("name", name, "graph", graph.toJson()));
    assertThat(saved.ok()).as(saved.body()).isTrue();
    return UUID.fromString(saved.json().path("id").asText());
  }

  @Test
  void runsTheWorkflowWithoutTheModelAndKeepsItInTheConversation() {
    UUID id = workflow("Simon workflow");
    workflow("Ticket booking");

    var picker = api.get("/api/workflows/runnable");
    assertThat(picker.ok()).as(picker.body()).isTrue();
    assertThat(picker.json().findValuesAsText("name")).contains("Simon workflow", "Ticket booking");

    var reply = api.post("/api/chat", Map.of("message", "/run simon workflow"));

    assertThat(reply.ok()).as(reply.body()).isTrue();
    assertThat(reply.json().path("text").asText()).startsWith("Started **Simon workflow**");
    JsonNode ran = reply.json().path("actions").get(0);
    assertThat(ran.path("type").asText()).isEqualTo("reused_workflow");
    assertThat(ran.path("payload").path("workflowId").asText()).isEqualTo(id.toString());
    // Not one model call: it works however much of the chat budget is left.
    assertThat(model.requests).isEmpty();

    var run = api.get("/api/runs/" + ran.path("payload").path("runId").asText());
    assertThat(run.ok()).as(run.body()).isTrue();
    assertThat(run.json().path("workflowId").asText()).isEqualTo(id.toString());

    String conversationId = reply.json().path("conversationId").asText();
    var messages = api.get("/api/conversations/" + conversationId + "/messages");
    assertThat(messages.body()).contains("/run simon workflow", "Started **Simon workflow**");
  }

  @Test
  void anUnclearNameOffersTheChoicesInstead() {
    workflow("Book movie tickets");
    workflow("Book train tickets");

    var reply = api.post("/api/chat", Map.of("message", "/run book"));

    assertThat(reply.ok()).as(reply.body()).isTrue();
    JsonNode ask = reply.json().path("actions").get(0);
    assertThat(ask.path("type").asText()).isEqualTo("ask_user");
    assertThat(ask.path("payload").path("options").toString())
        .contains("/run Book movie tickets", "/run Book train tickets");
    assertThat(model.requests).isEmpty();
  }
}
