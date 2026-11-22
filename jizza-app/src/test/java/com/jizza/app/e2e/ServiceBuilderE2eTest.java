package com.jizza.app.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.autonomy.AutonomyGate;
import com.jizza.engine.api.enums.AutonomyRule;
import com.jizza.engine.api.chat.ChatScope;
import com.jizza.engine.api.orchestrator.AgentDirectory;
import com.jizza.engine.api.orchestrator.UiAction;
import com.jizza.engine.api.tool.ToolCall;
import com.jizza.engine.api.tool.ToolResult;
import com.jizza.engine.api.util.Json;
import com.jizza.platform.usecase.connector.ServiceBuilderToolProvider;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Building a connector by chatting (K5): Jizza drafts a service from what it read, adds an API and
 * an action, tries it against the real service, sends the person to add the key, and publishes.
 * Trying a change and publishing always wait for the person.
 */
@org.junit.jupiter.api.condition.EnabledIf(
    value = "com.jizza.engine.persistence.support.TestDatabase#available",
    disabledReason = "no PostgreSQL: set JIZZA_TEST_DB_URL or start Docker")
class ServiceBuilderE2eTest extends E2eSupport {

  private static final String PASSWORD = "Correct-Horse-92";

  @LocalServerPort private int port;
  @Autowired private ServiceBuilderToolProvider builder;
  @Autowired private AgentDirectory agents;
  @Autowired private AutonomyGate gate;

  private ApiClient api;
  private UUID userId;
  private UUID jizza;
  private StubService stub;

  @BeforeEach
  void setUp() {
    api = new ApiClient(port);
    api.prime();
    String email = "builder-" + UUID.randomUUID() + "@jizza.local";
    api.post("/api/auth/register", Map.of("email", email, "password", PASSWORD));
    var login = api.post("/api/auth/login", Map.of("email", email, "password", PASSWORD));
    userId = UUID.fromString(login.json().path("userId").asText());
    jizza = agents.orchestrator(userId).id();
    stub = new StubService();
  }

  @AfterEach
  void tearDown() {
    stub.close();
  }

  private ToolResult call(String tool, Object args) {
    return builder.invoke(jizza, new ToolCall("t-" + UUID.randomUUID(), tool, Json.toNode(args)));
  }

  @Test
  void jizzaBuildsTriesAndPublishesAService() {
    assertThat(builder.toolsFor(jizza)).extracting(s -> s.name())
        .contains(ServiceBuilderToolProvider.DRAFT, ServiceBuilderToolProvider.PUBLISH);

    var draft = call(ServiceBuilderToolProvider.DRAFT, Map.of("name", "School portal",
        "baseUrl", stub.apiBaseUrl(), "auth", "api_key",
        "docsUrl", "https://school.example/docs"));
    assertThat(draft.ok()).as(String.valueOf(draft.error())).isTrue();
    String serviceId = draft.output().path("serviceId").asText();
    assertThat(draft.output().path("note").asText()).contains(ServiceBuilderToolProvider.ASK_CREDENTIALS);

    var added = call(ServiceBuilderToolProvider.ADD_API, Map.of("serviceId", serviceId,
        "name", "list_homework", "label", "List homework", "method", "GET",
        "path", "/open/pets", "inputs", List.of(Map.of("name", "week", "in", "query"))));
    assertThat(added.ok()).as(String.valueOf(added.error())).isTrue();

    var action = call(ServiceBuilderToolProvider.ADD_ACTION, Map.of("serviceId", serviceId,
        "api", "list_homework", "name", "list_homework", "label", "List homework"));
    assertThat(action.ok()).as(String.valueOf(action.error())).isTrue();
    assertThat(action.output().path("changesThings").asBoolean()).isFalse();

    // Without its key the try says what's missing.
    var keyless = call(ServiceBuilderToolProvider.TRY_READ, Map.of("serviceId", serviceId,
        "action", "list_homework"));
    assertThat(keyless.error()).contains(ServiceBuilderToolProvider.ASK_CREDENTIALS);

    // A typo names what there is.
    var wrong = call(ServiceBuilderToolProvider.TRY_READ, Map.of("serviceId", serviceId,
        "action", "list_homwork"));
    assertThat(wrong.error()).contains("list_homework");

    // The key goes in on the Authentication tab, behind a button on the reply.
    var scope = ChatScope.Scope.main(userId, UUID.randomUUID(), jizza, false);
    ToolResult asked = ChatScope.run(scope, () -> call(ServiceBuilderToolProvider.ASK_CREDENTIALS,
        Map.of("serviceId", serviceId, "what", "an API key from Settings")));
    assertThat(asked.ok()).isTrue();
    UiAction card = scope.actions().get(0);
    assertThat(card.type()).isEqualTo(UiAction.SHOW_STEPS);
    assertThat(card.payload().path("steps").get(0).path("button").path("path").asText())
        .isEqualTo("/connectors/" + serviceId + "/authentication");

    // Trying a change and publishing always ask the person, whatever their rules.
    assertThat(gate.evaluate(jizza, ServiceBuilderToolProvider.TRY_CHANGE, Json.obj()).rule())
        .isEqualTo(AutonomyRule.ASK);
    assertThat(gate.evaluate(jizza, ServiceBuilderToolProvider.PUBLISH, Json.obj()).rule())
        .isEqualTo(AutonomyRule.ASK);
    assertThat(gate.evaluate(jizza, ServiceBuilderToolProvider.ADD_API, Json.obj()).rule())
        .isEqualTo(AutonomyRule.ALLOW);

    // An API-key service can't be published before the key is added.
    var early = call(ServiceBuilderToolProvider.PUBLISH, Map.of("serviceId", serviceId));
    assertThat(early.ok()).isFalse();
    assertThat(early.error()).contains("Authentication tab");

    JsonNode listed = call(ServiceBuilderToolProvider.LIST, Map.of("serviceId", serviceId)).output();
    assertThat(listed.path("actions").get(0).path("name").asText()).isEqualTo("list_homework");
  }

  @Test
  void anOpenServiceIsPublishedAndAWebhookTriggerGetsItsAddress() {
    String serviceId = call(ServiceBuilderToolProvider.DRAFT, Map.of("name", "Open portal",
        "baseUrl", stub.apiBaseUrl())).output().path("serviceId").asText();
    call(ServiceBuilderToolProvider.ADD_API, Map.of("serviceId", serviceId,
        "name", "list_homework", "label", "List homework", "method", "GET",
        "path", "/open/pets", "inputs", List.of(Map.of("name", "week", "in", "query"))));
    call(ServiceBuilderToolProvider.ADD_ACTION, Map.of("serviceId", serviceId,
        "api", "list_homework", "name", "list_homework", "label", "List homework"));
    stub.setPets("[{\"id\":1,\"subject\":\"Maths\"}]");
    var tried = call(ServiceBuilderToolProvider.TRY_READ, Map.of("serviceId", serviceId,
        "action", "list_homework", "input", Map.of("week", "12")));
    assertThat(tried.ok()).as(String.valueOf(tried.error())).isTrue();
    assertThat(tried.output().toString()).contains("Maths");
    assertThat(stub.received.stream().anyMatch(r -> r.path().contains("week=12"))).isTrue();

    var trigger = call(ServiceBuilderToolProvider.ADD_TRIGGER, Map.of("serviceId", serviceId,
        "name", "homework_set", "label", "Homework set", "kind", "webhook"));
    assertThat(trigger.ok()).as(String.valueOf(trigger.error())).isTrue();
    assertThat(trigger.output().path("webhookUrl").asText()).contains("/api/webhooks/services/");

    var published = call(ServiceBuilderToolProvider.PUBLISH, Map.of("serviceId", serviceId));
    assertThat(published.ok()).as(String.valueOf(published.error())).isTrue();
    assertThat(published.output().path("status").asText()).isEqualTo("live");

    // Another person's Jizza can't reach it.
    ApiClient other = new ApiClient(port);
    other.prime();
    String email = "builder-other-" + UUID.randomUUID() + "@jizza.local";
    other.post("/api/auth/register", Map.of("email", email, "password", PASSWORD));
    var login = other.post("/api/auth/login", Map.of("email", email, "password", PASSWORD));
    UUID theirJizza = agents.orchestrator(
        UUID.fromString(login.json().path("userId").asText())).id();
    var reached = builder.invoke(theirJizza, new ToolCall("t", ServiceBuilderToolProvider.LIST,
        Json.toNode(Map.of("serviceId", serviceId))));
    assertThat(reached.ok()).isFalse();
  }
}
