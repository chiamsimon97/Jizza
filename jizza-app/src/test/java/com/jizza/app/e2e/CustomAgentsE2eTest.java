package com.jizza.app.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.dto.AgentDto;
import com.jizza.engine.api.llm.LlmResponse;
import com.jizza.engine.api.orchestrator.AgentDirectory;
import com.jizza.engine.api.tool.ToolCall;
import com.jizza.engine.api.util.Json;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Acceptance: people make their own agents and teams, which run through Jizza like any specialist
 * and are bounded by the same guardrails, with locks flowing down a team.
 */
@org.junit.jupiter.api.condition.EnabledIf(
    value = "com.jizza.engine.persistence.support.TestDatabase#available",
    disabledReason = "no PostgreSQL: set JIZZA_TEST_DB_URL or start Docker")
class CustomAgentsE2eTest extends E2eSupport {

  private static final String PASSWORD = "Correct-Horse-92";

  @LocalServerPort private int port;
  @Autowired private AgentDirectory agents;
  @Autowired private E2eSupport.ScriptedLlmClient model;

  private ApiClient api;
  private UUID userId;

  @BeforeEach
  void setUp() {
    api = new ApiClient(port);
    api.prime();
    model.reset();
    String email = "custom-" + UUID.randomUUID() + "@jizza.local";
    api.post("/api/auth/register", Map.of("email", email, "password", PASSWORD));
    var login = api.post("/api/auth/login", Map.of("email", email, "password", PASSWORD));
    userId = UUID.fromString(login.json().path("userId").asText());
  }

  private UUID agentId(String key) {
    return agents.byKey(userId, key).map(AgentDto::id).orElseThrow();
  }

  private Map<String, Object> wanda() {
    Map<String, Object> body = new HashMap<>();
    body.put("name", "Wanda");
    body.put("description", "Plans trips: flights, hotels and itineraries.");
    body.put("instructions", "You plan trips. Never book anything yourself.");
    return body;
  }

  private JsonNode create(Map<String, Object> body) {
    var created = api.post("/api/agents", body);
    assertThat(created.ok()).as(created.body()).isTrue();
    return created.json();
  }

  private Map<String, String> modes(String agentId) {
    var result = api.get("/api/agents/" + agentId + "/modes");
    assertThat(result.ok()).as(result.body()).isTrue();
    Map<String, String> out = new HashMap<>();
    for (JsonNode line : result.json()) {
      out.put(line.path("actionType").asText(), line.path("rule").asText());
    }
    return out;
  }

  private void stepUp() {
    var result = api.post("/api/auth/step-up", Map.of("password", PASSWORD));
    assertThat(result.ok()).as("step-up: %s", result.body()).isTrue();
  }

  @Test
  void aNewAgentJoinsJizzasTeamWithTheSafeProfileAndNoStepUp() {
    JsonNode agent = create(wanda());

    assertThat(agent.path("custom").asBoolean()).isTrue();
    assertThat(agent.path("key").asText()).startsWith("c_wanda_");
    assertThat(agent.path("status").asText()).isEqualTo("active");
    assertThat(agent.path("parentAgentId").asText()).isEqualTo(agentId("jizza").toString());
    assertThat(modes(agent.path("id").asText()))
        .containsEntry("READ", "ALLOW").containsEntry("DRAFT", "ALLOW")
        .containsEntry("SEND", "ASK").containsEntry("MONEY", "DENY");
  }

  @Test
  void namesThatWouldImpersonateAnotherAgentAreRefused() {
    Map<String, Object> asJizza = wanda();
    asJizza.put("name", "jizza");
    Map<String, Object> asKai = wanda();
    asKai.put("name", "Kai");
    Map<String, Object> noUseCase = wanda();
    noUseCase.remove("description");

    assertThat(api.post("/api/agents", asJizza).ok()).isFalse();
    assertThat(api.post("/api/agents", asKai).ok()).isFalse();
    assertThat(api.post("/api/agents", noUseCase).ok()).isFalse();
  }

  @Test
  void readingSharedMemoryOrLooseningARuleNeedsAFreshReauthentication() {
    Map<String, Object> withFinance = wanda();
    withFinance.put("memoryDomains", List.of("finance"));
    Map<String, Object> looser = wanda();
    looser.put("name", "Wanda Two");
    looser.put("modes", Map.of("SEND", "ALLOW"));

    var blocked = api.post("/api/agents", withFinance);
    assertThat(blocked.problemType()).endsWith("step-up-required");
    assertThat(api.post("/api/agents", looser).problemType()).endsWith("step-up-required");

    stepUp();
    JsonNode agent = create(withFinance);
    assertThat(agent.path("memoryDomains").get(0).asText()).isEqualTo("finance");
  }

  @Test
  void jizzaHandsWorkToACustomAgentLikeAnyOther() {
    String key = create(wanda()).path("key").asText();
    model.scriptFor("Jizza",
        new LlmResponse("", List.of(new ToolCall("c1", "delegate_to_specialist",
            Json.obj().put("agent_key", key).put("task", "flights to Lisbon next Friday"))),
            "tool_use", 10, 5, "m"),
        LlmResponse.text("Wanda found three flights."));
    model.scriptFor("Wanda", LlmResponse.text("TAP at 9am, easyJet at noon, BA at 6pm."));

    var reply = api.post("/api/chat", Map.of("message", "Find me flights to Lisbon"));

    assertThat(reply.ok()).as(reply.body()).isTrue();
    assertThat(reply.json().path("contributors").get(0).path("agentName").asText())
        .isEqualTo("Wanda");
  }

  @Test
  void aTeamFromTheGalleryGoesUnderCatoAndItsLocksCapEveryoneBelow() {
    String cato = agentId("professional").toString();
    var team = api.post("/api/agents/teams",
        Map.of("galleryId", "product-team", "parentAgentId", cato));
    assertThat(team.ok()).as(team.body()).isTrue();
    assertThat(team.json()).hasSize(4);

    String pm = null;
    String marketing = null;
    for (JsonNode a : team.json()) {
      if (a.path("name").asText().equals("Product manager")) pm = a.path("id").asText();
      if (a.path("name").asText().equals("Marketing lead")) marketing = a.path("id").asText();
    }
    assertThat(pm).isNotNull();
    var pmAgent = api.get("/api/agents/" + pm).json();
    assertThat(pmAgent.path("parentAgentId").asText()).isEqualTo(cato);

    // The product manager's Send lock is the marketing lead's ceiling.
    var lines = api.get("/api/agents/" + marketing + "/modes").json();
    JsonNode send = null;
    for (JsonNode line : lines) if (line.path("actionType").asText().equals("SEND")) send = line;
    assertThat(send.path("ceiling").asText()).isEqualTo("ASK");
    assertThat(send.path("ceilingFrom").asText()).isEqualTo("Product manager");

    // A report may not be made looser than a lock above it, even after step-up.
    stepUp();
    Map<String, Object> rogue = wanda();
    rogue.put("name", "Rogue sender");
    rogue.put("parentAgentId", pm);
    rogue.put("modes", Map.of("SEND", "ALLOW"));
    var refused = api.post("/api/agents", rogue);
    assertThat(refused.ok()).isFalse();
    assertThat(refused.body()).contains("locked");

    // The org chart shows the team under Cato.
    JsonNode tree = api.get("/api/agents/tree").json();
    List<String> underCato = new ArrayList<>();
    for (JsonNode node : tree.path("reports")) {
      if (node.path("agent").path("id").asText().equals(cato)) {
        node.path("reports").forEach(r -> underCato.add(r.path("agent").path("name").asText()));
      }
    }
    assertThat(underCato).contains("Product manager");
  }

  @Test
  void teamsCannotGoDeeperThanFourLevelsBelowJizza() {
    String parent = null;
    for (int level = 1; level <= 4; level++) {
      Map<String, Object> body = wanda();
      body.put("name", "Level " + level);
      if (parent != null) body.put("parentAgentId", parent);
      parent = create(body).path("id").asText();
    }
    Map<String, Object> tooDeep = wanda();
    tooDeep.put("name", "Level 5");
    tooDeep.put("parentAgentId", parent);

    var refused = api.post("/api/agents", tooDeep);
    assertThat(refused.ok()).isFalse();
    assertThat(refused.body()).contains("levels below Jizza");
  }

  @Test
  void archivingMovesReportsUpAndOnlyAnArchivedAgentCanBeDeleted() {
    String lead = create(wanda()).path("id").asText();
    Map<String, Object> report = wanda();
    report.put("name", "Hotel finder");
    report.put("parentAgentId", lead);
    String reportId = create(report).path("id").asText();

    assertThat(api.delete("/api/agents/" + lead).ok()).isFalse();

    var archived = api.post("/api/agents/" + lead + "/archive", Map.of("reports", "move_up"));
    assertThat(archived.ok()).as(archived.body()).isTrue();
    assertThat(archived.json().path("reportsMoved").asInt()).isEqualTo(1);
    assertThat(api.get("/api/agents/" + reportId).json().path("parentAgentId").asText())
        .isEqualTo(agentId("jizza").toString());
    assertThat(api.get("/api/agents").body()).doesNotContain(lead);
    assertThat(api.get("/api/agents?status=archived").body()).contains(lead);

    var restored = api.post("/api/agents/" + lead + "/restore", null);
    assertThat(restored.json().path("status").asText()).isEqualTo("active");
    api.post("/api/agents/" + lead + "/archive", Map.of());
    stepUp();
    assertThat(api.delete("/api/agents/" + lead).ok()).isTrue();
    assertThat(api.get("/api/agents/" + lead).ok()).isFalse();
  }

  @Test
  void builtInsCannotBeArchivedOrRewritten() {
    String kai = agentId("finance").toString();
    assertThat(api.post("/api/agents/" + kai + "/archive", Map.of()).ok()).isFalse();
    assertThat(api.patch("/api/agents/" + kai, Map.of("instructions", "Move money freely")).ok())
        .isFalse();
  }

  @Test
  void editingInstructionsKeepsAHistoryThatCanBeRolledBack() {
    String id = create(wanda()).path("id").asText();
    var edited = api.patch("/api/agents/" + id, Map.of("instructions", "Only plan train trips."));
    assertThat(edited.ok()).as(edited.body()).isTrue();

    var revisions = api.get("/api/agents/" + id + "/revisions").json();
    assertThat(revisions).hasSize(2);
    api.post("/api/agents/" + id + "/revisions/1/restore", null);

    assertThat(api.get("/api/agents/" + id).json().path("systemPrompt").asText())
        .isEqualTo("You plan trips. Never book anything yourself.");
    assertThat(api.get("/api/agents/" + id + "/revisions").json()).hasSize(3);
  }

  @Test
  void aTrialRunsTheAgentAndReportsItsAnswer() {
    String id = create(wanda()).path("id").asText();
    model.scriptFor("Wanda", LlmResponse.text("I'd look at TAP and easyJet."));

    var trial = api.post("/api/agents/" + id + "/try", Map.of("message", "Flights to Lisbon?"));

    assertThat(trial.ok()).as(trial.body()).isTrue();
    assertThat(trial.json().path("answer").asText()).contains("TAP");
  }

  @Test
  void jizzaCanDraftAnAgentButOnlyTheUserCreatesIt() {
    var draft = Json.mapper().createArrayNode();
    draft.addObject().put("name", "Bill watcher")
        .put("description", "Watches utility bills for rises.")
        .put("instructions", "Notice when a bill jumps and say by how much.");
    model.scriptFor("Jizza",
        new LlmResponse("", List.of(new ToolCall("c1", "app_propose_agent",
            Json.obj().set("agents", draft))), "tool_use", 10, 5, "m"),
        LlmResponse.text("Here's a draft of a bill watcher for you to check."));

    var reply = api.post("/api/chat", Map.of("message", "I want an agent that watches my bills",
        "screen", "agents"));

    assertThat(reply.ok()).as(reply.body()).isTrue();
    assertThat(reply.body()).contains("propose_agent").contains("Bill watcher");
    assertThat(api.get("/api/agents").body()).doesNotContain("Bill watcher");
  }
}
