package com.jizza.app.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Two separate accounts on one server. Everything one of them owns is unreachable to the other
 * through every route that takes its id, read or write, and the owner's data is still intact
 * after the other account has tried each of them.
 *
 * <p>The routes come from Spring's handler mapping, so a route added later is swept as well.
 */
@org.junit.jupiter.api.condition.EnabledIf(
    value = "com.jizza.engine.persistence.support.TestDatabase#available",
    disabledReason = "no PostgreSQL: set JIZZA_TEST_DB_URL or start Docker")
class CrossAccountIsolationE2eTest extends E2eSupport {

  private static final String PASSWORD = "Correct-Horse-92";
  private static final Pattern VAR = Pattern.compile("\\{([^}]+)}");

  @LocalServerPort private int port;
  @Autowired @Qualifier("requestMappingHandlerMapping")
  private RequestMappingHandlerMapping mappings;
  @Autowired private E2eSupport.ScriptedLlmClient model;

  private ApiClient owner;
  private ApiClient other;
  /** Route prefix (up to the first id) to the owner's id for it. */
  private final Map<String, String> owned = new LinkedHashMap<>();

  @BeforeEach
  void setUp() {
    model.reset();
    owner = account();
    other = account();

    JsonNode agents = owner.get("/api/agents").json();
    String agentId = agents.get(0).path("id").asText();
    owned.put("/api/agents/", agentId);

    var conversation = owner.post("/api/conversations", Map.of("title", "Owner's private chat"));
    assertThat(conversation.ok()).as(conversation.body()).isTrue();
    owned.put("/api/conversations/", conversation.json().path("id").asText());

    var workflow = owner.post("/api/workflows", Map.of("name", "Owner's workflow", "graph",
        com.jizza.engine.api.util.Json.read("""
            {"nodes":[{"id":"start","type":"TRIGGER"},{"id":"end","type":"END"}],"edges":[{"from":"start","to":"end"}]}""")));
    assertThat(workflow.ok()).as(workflow.body()).isTrue();
    owned.put("/api/workflows/", workflow.json().path("id").asText());

    var task = owner.post("/api/tasks", Map.of("title", "Owner's task", "agentId", agentId,
        "scheduleType", "ONE_TIME", "runAt", Instant.now().plusSeconds(86_400).toString()));
    assertThat(task.ok()).as(task.body()).isTrue();
    owned.put("/api/tasks/", task.json().path("id").asText());

    var memory = owner.post("/api/memory", Map.of("category", "PREFERENCE",
        "content", "Owner prefers tea"));
    assertThat(memory.ok()).as(memory.body()).isTrue();
    owned.put("/api/memory/", memory.json().path("memory").path("id").asText());

    var subject = owner.post("/api/memory/subjects", Map.of("kind", "PERSON", "name", "Sam"));
    assertThat(subject.ok()).as(subject.body()).isTrue();
    owned.put("/api/memory/subjects/", subject.json().path("id").asText());

    var service = owner.post("/api/services", Map.of("name", "Owner's service",
        "baseUrl", "https://api.example.com", "authScheme", "API_KEY"));
    assertThat(service.ok()).as(service.body()).isTrue();
    owned.put("/api/services/", service.json().path("id").asText());

    // Kai Portfolio: someone the owner manages, and that person's consent note.
    var person = owner.post("/api/finance/people", Map.of("displayName", "Owner's mother",
        "kind", "parent", "type", "individual"));
    assertThat(person.ok()).as(person.body()).isTrue();
    owned.put("/api/finance/people/", person.json().path("id").asText());
    var consent = owner.post("/api/finance/people/" + person.json().path("id").asText()
        + "/consents", Map.of("scope", "manage"));
    assertThat(consent.ok()).as(consent.body()).isTrue();
    owned.put("/api/finance/consents/", consent.json().path("id").asText());

    owned.forEach((prefix, id) -> assertThat(id).as("owner's id under %s", prefix).isNotBlank());
  }

  private ApiClient account() {
    String email = "isolation-" + UUID.randomUUID() + "@jizza.local";
    ApiClient client = new ApiClient(port);
    client.prime();
    assertThat(client.post("/api/auth/register", Map.of("email", email, "password", PASSWORD))
        .ok()).isTrue();
    assertThat(client.post("/api/auth/login", Map.of("email", email, "password", PASSWORD))
        .ok()).isTrue();
    return client;
  }

  /** The route with the owner's id in its first variable, or null when it has none of theirs. */
  private String withOwnersId(String pattern) {
    Matcher m = VAR.matcher(pattern);
    if (!m.find()) return null;
    String prefix = pattern.substring(0, m.start());
    String id = owned.get(prefix);
    if (id == null) return null;
    StringBuilder sb = new StringBuilder(prefix).append(id);
    String rest = pattern.substring(m.end());
    sb.append(VAR.matcher(rest).replaceAll(r -> UUID.randomUUID().toString()));
    return sb.toString();
  }

  @Test
  void noRouteLetsAnotherAccountReadOrChangeWhatTheOwnerHas() {
    List<String> leaks = new ArrayList<>();
    int tried = 0;
    for (var entry : mappings.getHandlerMethods().entrySet()) {
      var info = entry.getKey();
      for (String pattern : info.getPatternValues()) {
        String path = withOwnersId(pattern);
        if (path == null) continue;
        var methods = info.getMethodsCondition().getMethods();
        for (RequestMethod method : methods.isEmpty() ? java.util.Set.of(RequestMethod.GET) : methods) {
          tried++;
          var result = other.send(method.name(), path, method == RequestMethod.GET ? null : Map.of());
          if (result.ok()) leaks.add(method + " " + pattern + " -> " + result.status() + " "
              + (result.body().length() > 200 ? result.body().substring(0, 200) : result.body()));
        }
      }
    }
    assertThat(tried).as("routes swept").isGreaterThan(60);
    assertThat(leaks).as("routes another account could use on the owner's data").isEmpty();

    // And nothing the other account tried changed anything of the owner's.
    for (var e : owned.entrySet()) {
      String path = e.getKey() + e.getValue();
      if (e.getKey().equals("/api/memory/subjects/")) continue; // no single-subject GET
      if (e.getKey().equals("/api/finance/consents/")) continue; // read through the person
      var still = owner.get(path);
      assertThat(still.ok()).as("owner still has %s: %s", path, still.body()).isTrue();
    }
  }

  /**
   * The other way in: not the owner's id in the path, but in a request body, naming something of
   * the owner's for the caller's own new thing to use.
   */
  @Test
  void anotherAccountsIdsInARequestBodyAreRefused() {
    String ownersAgent = owned.get("/api/agents/");
    String ownersWorkflow = owned.get("/api/workflows/");
    String ownersMemory = owned.get("/api/memory/");
    String othersAgent = other.get("/api/agents").json().get(0).path("id").asText();

    var task = other.post("/api/tasks", Map.of("title", "Borrowed agent", "agentId", ownersAgent,
        "scheduleType", "ONE_TIME", "runAt", Instant.now().plusSeconds(86_400).toString()));
    assertThat(task.status()).as("task with the owner's agent: %s", task.body()).isBetween(400, 404);

    var rule = other.put("/api/guardrails/rules", Map.of("agentId", ownersAgent,
        "toolRef", "anything", "rule", "ALLOW", "precedence", 1));
    assertThat(rule.ok()).as("guardrail on the owner's agent: %s", rule.body()).isFalse();

    var simulate = other.post("/api/guardrails/simulate",
        Map.of("agentId", ownersAgent, "toolRef", "anything"));
    assertThat(simulate.ok()).as("simulating the owner's agent: %s", simulate.body()).isFalse();

    var workflow = other.post("/api/workflows", Map.of("name", "Borrowed", "owningAgentId",
        ownersAgent, "graph", com.jizza.engine.api.util.Json.read("""
            {"nodes":[{"id":"start","type":"TRIGGER"},{"id":"end","type":"END"}],
             "edges":[{"from":"start","to":"end"}]}""")));
    assertThat(workflow.ok()).as("workflow owned by the owner's agent: %s", workflow.body())
        .isFalse();

    var overwrite = other.post("/api/workflows", Map.of("id", ownersWorkflow, "name", "Hijacked",
        "graph", com.jizza.engine.api.util.Json.read("""
            {"nodes":[{"id":"start","type":"TRIGGER"},{"id":"end","type":"END"}],
             "edges":[{"from":"start","to":"end"}]}""")));
    assertThat(overwrite.ok()).as("saving over the owner's workflow: %s", overwrite.body())
        .isFalse();
    assertThat(owner.get("/api/workflows/" + ownersWorkflow).body()).doesNotContain("Hijacked");

    var share = other.post("/api/memory/" + ownersMemory + "/share", Map.of("agentId", othersAgent));
    assertThat(share.ok()).isFalse();
  }

  @Test
  void listsOnlyEverShowTheCallersOwnThings() {
    for (String list : List.of("/api/conversations", "/api/workflows", "/api/tasks", "/api/memory",
        "/api/memory/subjects", "/api/services", "/api/agents", "/api/notifications",
        "/api/runs", "/api/activity?limit=200", "/api/procedures", "/api/finance/people",
        "/api/finance/profile", "/api/finance/config")) {
      String body = other.get(list).body();
      for (String id : owned.values()) {
        assertThat(body).as("%s shows the owner's %s", list, id).doesNotContain(id);
      }
    }
  }
}
