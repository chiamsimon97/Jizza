package com.jizza.app.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.llm.LlmResponse;
import com.jizza.engine.api.llm.Msg;
import com.jizza.engine.api.tool.ToolCall;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.memory.config.MemoryProperties;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Acceptance for hybrid memory, through the HTTP API and the real chat path: what the user told
 * Jizza is handed to the right agent automatically, scoped to that agent's domain, and the user can
 * see, correct and delete all of it.
 */
@org.junit.jupiter.api.condition.EnabledIf(
    value = "com.jizza.engine.persistence.support.TestDatabase#available",
    disabledReason = "no PostgreSQL: set JIZZA_TEST_DB_URL or start Docker")
class MemoryE2eTest extends E2eSupport {

  private static final String PASSWORD = "Correct-Horse-92";

  @LocalServerPort private int port;
  @Autowired private E2eSupport.ScriptedLlmClient model;
  @Autowired private MemoryProperties memoryProps;

  private ApiClient api;
  private String priyaId;
  private String sipId;

  @BeforeEach
  void setUp() {
    api = new ApiClient(port);
    api.prime();
    model.reset();
    String email = "memory-" + UUID.randomUUID() + "@jizza.local";
    api.post("/api/auth/register", Map.of("email", email, "password", PASSWORD));
    api.post("/api/auth/login", Map.of("email", email, "password", PASSWORD));

    // What the user told Jizza about their wife before. Creating a connector needs a fresh step-up.
    api.post("/api/auth/step-up", Map.of("password", PASSWORD));
    var connection = api.post("/api/connections", Map.of(
        "serviceName", "Zerodha", "authScheme", "API_KEY", "clientSecret", "k",
        "baseUrl", "https://api.kite.example.com"));
    assertThat(connection.ok()).as(connection.body()).isTrue();

    var priya = api.post("/api/memory/subjects", Map.of("kind", "PERSON", "name", "Priya", "relation", "wife"));
    assertThat(priya.ok()).as(priya.body()).isTrue();
    priyaId = priya.json().path("id").asText();
    var account = api.post("/api/memory/subjects", Map.of("kind", "ACCOUNT",
        "name", "Priya's Zerodha account", "connectionId", connection.json().path("id").asText(),
        "domains", List.of("finance")));
    assertThat(account.ok()).as(account.body()).isTrue();
    api.post("/api/memory/subjects/" + account.json().path("id").asText() + "/relations",
        Map.of("toSubjectId", priyaId, "relation", "held_by"));

    remember("RELATIONSHIP", "Priya is the user's wife and works as a school teacher", null);
    sipId = remember("FINANCE", "Priya invests Rs 20,000 a month in a Nifty 50 index fund SIP", "monthly_sip");
    remember("FINANCE", "Priya's risk tolerance is moderate", "risk_tolerance");
    remember("HEALTH", "Priya is allergic to penicillin", null);
  }

  private String remember(String category, String content, String key) {
    var body = new java.util.HashMap<String, Object>(Map.of(
        "category", category, "content", content, "subjectIds", List.of(priyaId)));
    if (key != null) body.put("key", key);
    var result = api.post("/api/memory", body);
    assertThat(result.ok()).as(result.body()).isTrue();
    assertThat(result.json().path("outcome").asText()).isEqualTo("CREATED");
    return result.json().path("memory").path("id").asText();
  }

  private static LlmResponse delegate(String agentKey, String task) {
    return new LlmResponse("", List.of(new ToolCall("call-" + agentKey, "delegate_to_specialist",
        Json.obj().put("agent_key", agentKey).put("task", task))), "tool_use", 10, 5, "m");
  }

  private String systemPromptOf(String agentName) {
    synchronized (model.requests) {
      for (List<Msg> request : model.requests) {
        Msg first = request.get(0);
        if (first.role() == Msg.Role.SYSTEM && first.text().startsWith("Your name is " + agentName + ".")) {
          return first.text();
        }
      }
    }
    throw new AssertionError(agentName + " was never asked anything");
  }

  @Test
  void anInvestmentQuestionAboutTheWifeReachesKaiWithHerPlanAndAccountAlreadyInContext() {
    model.scriptFor("Jizza",
        delegate("finance", "Should my wife increase her monthly SIP this year?"),
        LlmResponse.text("Kai suggests stepping Priya's SIP up gradually."));
    model.scriptFor("Kai", LlmResponse.text("Given her moderate risk tolerance, step it up 10%."));

    var reply = api.post("/api/chat", Map.of("message", "Should my wife invest more this year?"));
    assertThat(reply.ok()).as(reply.body()).isTrue();

    // Kai was told nothing about Priya by Jizza beyond the task, yet starts informed.
    assertThat(systemPromptOf("Kai"))
        .contains("What you already know about the user")
        .contains("Priya (wife; person)")
        .contains("Nifty 50 index fund SIP")
        .contains("risk tolerance is moderate")
        .contains("connector \"Zerodha\"")
        .doesNotContain("penicillin");
    // Jizza knows who the wife is, which is all it needs to delegate; the detail stays with Kai.
    assertThat(systemPromptOf("Jizza"))
        .contains("school teacher")
        .doesNotContain("Nifty")
        .doesNotContain("penicillin");
  }

  @Test
  void theUserCanPreviewSearchCorrectAndForgetTheirMemory() {
    var preview = api.post("/api/memory/preview",
        Map.of("agentKey", "finance", "request", "rebalance my wife's portfolio"));
    assertThat(preview.ok()).as(preview.body()).isTrue();
    assertThat(preview.json().path("context").asText()).contains("Nifty").doesNotContain("penicillin");

    var search = api.get("/api/memory?q=index%20fund");
    assertThat(search.ok()).as(search.body()).isTrue();
    assertThat(search.json().get(0).path("id").asText()).isEqualTo(sipId);

    var corrected = api.patch("/api/memory/" + sipId,
        Map.of("content", "Priya invests Rs 25,000 a month in a Nifty 50 index fund SIP"));
    assertThat(corrected.ok()).as(corrected.body()).isTrue();
    var detail = api.get("/api/memory/" + sipId);
    assertThat(detail.json().path("memory").path("content").asText()).contains("25,000");
    assertThat(detail.json().path("history").toString()).contains("20,000");

    assertThat(api.delete("/api/memory/" + sipId).ok()).isTrue();
    assertThat(api.get("/api/memory/" + sipId).status()).isEqualTo(404);
  }

  @Test
  void anotherUserCannotSeeOrTouchIt() {
    ApiClient stranger = new ApiClient(port);
    stranger.prime();
    String email = "stranger-" + UUID.randomUUID() + "@jizza.local";
    stranger.post("/api/auth/register", Map.of("email", email, "password", PASSWORD));
    stranger.post("/api/auth/login", Map.of("email", email, "password", PASSWORD));

    assertThat(stranger.get("/api/memory/" + sipId).status()).isEqualTo(404);
    // Forgetting asks for a fresh step-up first; even with one, someone else's memory is not there.
    stranger.post("/api/auth/step-up", Map.of("password", PASSWORD));
    assertThat(stranger.delete("/api/memory/" + sipId).status()).isEqualTo(404);
    assertThat(stranger.get("/api/memory").json()).isEmpty();
    assertThat(stranger.get("/api/memory/subjects").json()).isEmpty();
    assertThat(api.get("/api/memory/" + sipId).ok()).isTrue();
  }

  @Test
  void durableFactsAreLearnedFromChatUnlessTheUserTurnsThatOff() {
    memoryProps.getExtraction().setEnabled(true);
    try {
      model.scriptFor("Jizza", LlmResponse.text("Noted, I'll keep that in mind."));
      // The extractor is not an agent, so it takes the next unnamed scripted reply.
      model.script(LlmResponse.text("""
          {"facts":[{"content":"Priya wants to retire by 55","category":"finance","key":"retirement_goal",
                     "about":[{"name":"Priya","kind":"person"}],"confidence":0.9,"importance":0.6}],
           "links":[]}"""));
      api.post("/api/chat", Map.of("message", "By the way, my wife Priya wants to retire by 55."));

      JsonNode learned = api.get("/api/memory?subjectId=" + priyaId).json();
      JsonNode fact = null;
      for (JsonNode m : learned) if (m.path("content").asText().contains("retire by 55")) fact = m;
      assertThat(fact).as(learned.toString()).isNotNull();
      assertThat(fact.path("source").asText()).isEqualTo("CONVERSATION");
      assertThat(fact.path("confirmed").asBoolean()).isFalse();

      // Consent withdrawn: nothing more is learned, and the model is not even asked.
      assertThat(api.put("/api/memory/settings", Map.of("autoLearn", false)).ok()).isTrue();
      model.reset();
      model.scriptFor("Jizza", LlmResponse.text("Okay."));
      api.post("/api/chat", Map.of("message", "Also, my wife Priya prefers gold over equities."));
      assertThat(model.requests).hasSize(1);
    } finally {
      memoryProps.getExtraction().setEnabled(false);
    }
  }

  @Test
  void theExportCarriesTheMemory() {
    var export = api.get("/api/data/export");
    assertThat(export.ok()).as(export.body()).isTrue();
    assertThat(export.json().path("memory").path("subjects")).hasSize(2);
    assertThat(export.json().path("memory").path("facts").toString()).contains("Nifty 50");
  }
}
