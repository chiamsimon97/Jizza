package com.jizza.app.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.jizza.engine.api.enums.NodeType;
import com.jizza.engine.api.enums.WorkflowStatus;
import com.jizza.engine.api.llm.LlmResponse;
import com.jizza.engine.api.tool.ToolCall;
import com.jizza.engine.api.tool.ToolSpec;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.workflow.graph.WorkflowEdge;
import com.jizza.engine.api.workflow.graph.WorkflowGraph;
import com.jizza.engine.api.workflow.graph.WorkflowNode;
import com.jizza.engine.persistence.repository.WorkflowRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Acceptance: an MCP server added from the app, as a custom connector is in Claude. Its tools are
 * discovered over the real MCP client, offered to Jizza in chat, and usable as a workflow step,
 * with the token kept out of every reply and out of the model's context.
 */
@org.junit.jupiter.api.condition.EnabledIf(
    value = "com.jizza.engine.persistence.support.TestDatabase#available",
    disabledReason = "no PostgreSQL: set JIZZA_TEST_DB_URL or start Docker")
class McpServersE2eTest extends E2eSupport {

  private static final String PASSWORD = "Correct-Horse-92";
  private static final String TOKEN = "kite-secret-token-77";

  @LocalServerPort private int port;
  @Autowired private E2eSupport.ScriptedLlmClient model;
  @Autowired private WorkflowRepository workflows;

  private ApiClient api;
  private StubMcpServer kite;
  private String serverId;

  @BeforeEach
  void setUp() {
    kite = new StubMcpServer();
    api = new ApiClient(port);
    api.prime();
    model.reset();
    String email = "mcp-" + UUID.randomUUID() + "@jizza.local";
    api.post("/api/auth/register", Map.of("email", email, "password", PASSWORD));
    api.post("/api/auth/login", Map.of("email", email, "password", PASSWORD));
    api.post("/api/auth/step-up", Map.of("password", PASSWORD));

    Map<String, Object> body = new HashMap<>();
    body.put("name", "Kite");
    body.put("url", kite.url());
    body.put("authType", "BEARER");
    body.put("secret", TOKEN);
    var created = api.post("/api/mcp-servers", body);
    assertThat(created.ok()).as(created.body()).isTrue();
    serverId = created.json().path("id").asText();
  }

  @AfterEach
  void tearDown() {
    kite.close();
  }

  @Test
  void addingAServerDiscoversItsToolsAndNeverShowsTheToken() {
    var server = api.get("/api/mcp-servers/" + serverId);
    assertThat(server.json().path("status").asText()).as(server.body()).isEqualTo("connected");
    assertThat(server.json().path("serverName").asText()).isEqualTo("stub-kite");
    assertThat(server.json().path("tools").findValuesAsText("toolName"))
        .containsExactly("mcp_kite__login", "mcp_kite__get_holdings", "mcp_kite__place_order");
    // What each tool does is suggested from its name, so the agents' guardrails apply at once.
    var tools = server.json().path("tools");
    assertThat(tools.findValuesAsText("actionType")).containsExactly("READ", "READ", "MONEY");
    assertThat(tools.get(1).path("suggested").asBoolean()).isTrue();
    assertThat(server.json().path("secretPresent").asBoolean()).isTrue();
    assertThat(server.body()).doesNotContain(TOKEN);
    assertThat(kite.authorizations).contains("Bearer " + TOKEN);
    assertThat(api.get("/api/mcp-servers").json().findValuesAsText("id")).contains(serverId);
  }

  @Test
  void tryingAToolTeachesItsOutputFields() {
    var run = api.post("/api/mcp-servers/" + serverId + "/tools/get_holdings/test", Json.obj());
    assertThat(run.ok()).as(run.body()).isTrue();
    assertThat(run.json().path("output").path("holdings").path(0).path("symbol").asText())
        .isEqualTo("INFY");

    var tool = api.get("/api/mcp-servers/" + serverId).json().path("tools").get(1);
    assertThat(tool.path("outputSource").asText()).isEqualTo("learnt");
    assertThat(tool.path("outputSchema").path("properties").path("holdings").path("items")
        .path("properties").has("symbol")).isTrue();
    assertThat(tool.path("outputSchema").toString()).doesNotContain("INFY");
  }

  @Test
  void jizzaUsesItsToolsInChat() {
    assertThat(api.patch("/api/mcp-servers/" + serverId + "/tools/login",
        Map.of("enabled", false)).ok()).isTrue();

    model.scriptFor("Jizza",
        new LlmResponse("", List.of(new ToolCall("m1", "mcp_kite__get_holdings", Json.obj())),
            "tool_use", 5, 5, "m"),
        LlmResponse.text("You hold 10 INFY."));

    var reply = api.post("/api/chat", Map.of("message", "what do I hold on Kite?"));

    assertThat(reply.ok()).as(reply.body()).isTrue();
    assertThat(kite.calls).containsExactly("get_holdings {}");
    List<String> offered = model.offeredTools.stream().flatMap(List::stream).map(ToolSpec::name)
        .distinct().toList();
    // A read runs under Jizza's rule for reads; off, or moving money (which Jizza never does by
    // default), it is not offered.
    assertThat(offered).contains("mcp_kite__get_holdings")
        .doesNotContain("mcp_kite__place_order", "mcp_kite__login");
    assertThat(model.everythingTheModelSaw()).contains("INFY").doesNotContain(TOKEN);
  }

  @Test
  void aWorkflowStepCallsATool() {
    UUID workflowId = approvedWorkflow(Json.obj().put("serverId", serverId).put("tool", "get_holdings"));
    // Classified as moving money, it stops rather than running.
    api.patch("/api/mcp-servers/" + serverId + "/tools/get_holdings", Map.of("actionType", "MONEY"));
    var waiting = api.post("/api/workflows/" + workflowId + "/run", Map.of("version", 1));
    assertThat(waiting.json().path("status").asText()).as(waiting.body())
        .isEqualTo("PAUSED_WAITING_INSTRUCTION");
    assertThat(kite.calls).isEmpty();

    api.patch("/api/mcp-servers/" + serverId + "/tools/get_holdings", Map.of("actionType", "READ"));
    var run = api.post("/api/workflows/" + workflowId + "/run", Map.of("version", 1));
    assertThat(run.json().path("status").asText()).as(run.body()).isEqualTo("DONE");
    assertThat(kite.calls).containsExactly("get_holdings {}");
  }

  @Test
  void anotherAccountCannotSeeOrUseIt() {
    var other = new ApiClient(port);
    other.prime();
    String email = "mcp-other-" + UUID.randomUUID() + "@jizza.local";
    other.post("/api/auth/register", Map.of("email", email, "password", PASSWORD));
    other.post("/api/auth/login", Map.of("email", email, "password", PASSWORD));
    assertThat(other.get("/api/mcp-servers/" + serverId).status()).isEqualTo(404);
    assertThat(other.get("/api/mcp-servers").json().findValuesAsText("id")).doesNotContain(serverId);

    assertThat(api.delete("/api/mcp-servers/" + serverId).ok()).isTrue();
    assertThat(api.get("/api/mcp-servers/" + serverId).status()).isEqualTo(404);
  }

  private UUID approvedWorkflow(com.fasterxml.jackson.databind.JsonNode config) {
    WorkflowGraph graph = new WorkflowGraph(
        List.of(new WorkflowNode("start", NodeType.TRIGGER, "start", null, null),
            new WorkflowNode("holdings", NodeType.MCP_TOOL, "holdings", config,
                new WorkflowNode.RetryPolicy(1, 1, 1.0, 50)),
            new WorkflowNode("finish", NodeType.END, "finish", null, null)),
        List.of(new WorkflowEdge("start", "holdings", null),
            new WorkflowEdge("holdings", "finish", null)));
    var saved = api.post("/api/workflows", Map.of("name", "Holdings", "graph", graph.toJson()));
    assertThat(saved.ok()).as(saved.body()).isTrue();
    UUID id = UUID.fromString(saved.json().path("id").asText());
    var entity = workflows.findById(id).orElseThrow();
    entity.setStatus(WorkflowStatus.APPROVED);
    workflows.save(entity);
    return id;
  }
}
