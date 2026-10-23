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
      var still = owner.get(path);
      assertThat(still.ok()).as("owner still has %s: %s", path, still.body()).isTrue();
    }
  }

  @Test
  void listsOnlyEverShowTheCallersOwnThings() {
    for (String list : List.of("/api/conversations", "/api/workflows", "/api/tasks", "/api/memory",
        "/api/memory/subjects", "/api/services", "/api/agents", "/api/notifications",
        "/api/runs", "/api/activity?limit=200", "/api/procedures")) {
      String body = other.get(list).body();
      for (String id : owned.values()) {
        assertThat(body).as("%s shows the owner's %s", list, id).doesNotContain(id);
      }
    }
  }
}
