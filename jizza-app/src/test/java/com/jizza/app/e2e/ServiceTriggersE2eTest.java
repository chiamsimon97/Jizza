package com.jizza.app.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.enums.NodeType;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.workflow.graph.WorkflowEdge;
import com.jizza.engine.api.workflow.graph.WorkflowGraph;
import com.jizza.engine.api.workflow.graph.WorkflowNode;
import com.jizza.engine.persistence.entity.WorkflowRunEntity;
import com.jizza.engine.persistence.repository.WorkflowRunRepository;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Service triggers start workflows (K6): an instant trigger the service calls, signed or not, and
 * a polling trigger that finds new items. A workflow listens to one trigger and gets each new
 * item's fields as its input.
 */
@org.junit.jupiter.api.condition.EnabledIf(
    value = "com.jizza.engine.persistence.support.TestDatabase#available",
    disabledReason = "no PostgreSQL: set JIZZA_TEST_DB_URL or start Docker")
class ServiceTriggersE2eTest extends E2eSupport {

  private static final String PASSWORD = "Correct-Horse-92";

  @LocalServerPort private int port;
  @Autowired private WorkflowRunRepository runs;
  @Autowired private com.jizza.engine.api.service.ServiceRunner runner;

  private ApiClient api;
  private UUID userId;
  private StubService stub;

  @BeforeEach
  void setUp() {
    api = new ApiClient(port);
    api.prime();
    String email = "triggers-" + UUID.randomUUID() + "@jizza.local";
    api.post("/api/auth/register", Map.of("email", email, "password", PASSWORD));
    var login = api.post("/api/auth/login", Map.of("email", email, "password", PASSWORD));
    userId = UUID.fromString(login.json().path("userId").asText());
    assertThat(api.post("/api/auth/step-up", Map.of("password", PASSWORD)).ok()).isTrue();
  }

  @AfterEach
  void tearDown() {
    if (stub != null) stub.close();
  }

  private String service(String baseUrl) {
    var created = api.post("/api/services", Map.of("name", "School portal", "description", "Homework",
        "baseUrl", baseUrl, "authScheme", "NO_AUTH"));
    assertThat(created.ok()).as(created.body()).isTrue();
    return created.json().path("id").asText();
  }

  private UUID workflow() {
    var graph = new WorkflowGraph(List.of(
        new WorkflowNode("start", NodeType.TRIGGER, "Start", Json.obj(), null),
        new WorkflowNode("tell", NodeType.NOTIFY, "Tell me", Json.obj()
            .put("title", "Homework").put("message", "New homework in {{ input.subject }}")
            .put("channel", "app"), null),
        new WorkflowNode("end", NodeType.END, "Done", Json.obj(), null)),
        List.of(new WorkflowEdge("start", "tell", null), new WorkflowEdge("tell", "end", null)));
    var saved = api.post("/api/workflows", Map.of("name", "Homework alert", "graph", graph.toJson()));
    assertThat(saved.ok()).as(saved.body()).isTrue();
    UUID id = UUID.fromString(saved.json().path("id").asText());
    assertThat(api.put("/api/workflows/" + id + "/enabled", Map.of("enabled", true)).ok()).isTrue();
    return id;
  }

  private List<WorkflowRunEntity> runsOf(UUID workflowId) {
    return runs.findByUserIdOrderByCreatedAtDesc(userId).stream()
        .filter(r -> r.getWorkflowId().equals(workflowId)).toList();
  }

  private static String hmac(String secret, String body) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void anInstantTriggerStartsItsWorkflowWithTheEventAsInput() throws Exception {
    String id = service("https://school.example");
    var trigger = api.post("/api/services/" + id + "/triggers", Map.of("name", "homework_set",
        "label", "Homework set", "kind", "webhook", "idField", "id",
        "signingSecret", "portal-secret-1", "signatureHeader", "X-Portal-Signature",
        "signaturePrefix", "sha256="));
    assertThat(trigger.ok()).as(trigger.body()).isTrue();
    String url = trigger.json().path("webhookUrl").asText();
    assertThat(url).contains("/api/webhooks/services/");
    assertThat(trigger.json().path("signed").asBoolean()).isTrue();
    assertThat(trigger.body()).doesNotContain("portal-secret-1");
    String path = url.substring(url.indexOf("/api/webhooks/services/"));
    String triggerId = trigger.json().path("id").asText();

    UUID wf = workflow();
    var listened = api.put("/api/workflows/" + wf + "/service-trigger", Map.of("triggerId", triggerId));
    assertThat(listened.ok()).as(listened.body()).isTrue();
    var choices = api.get("/api/service-triggers").json();
    assertThat(choices.get(0).path("workflowIds").get(0).asText()).isEqualTo(wf.toString());

    ApiClient portal = new ApiClient(port);
    Map<String, Object> event = Map.of("id", "hw-1", "subject", "Maths");
    String body = Json.write(event);

    // Not published yet: the address answers but starts nothing.
    var early = portal.sendWithHeaders("POST", path, event,
        Map.of("X-Portal-Signature", "sha256=" + hmac("portal-secret-1", body)));
    assertThat(early.status()).isEqualTo(403);
    var published = api.post("/api/services/" + id + "/publish", null);
    assertThat(published.ok()).as(published.body()).isTrue();

    var forged = portal.sendWithHeaders("POST", path, event, Map.of("X-Portal-Signature", "sha256=00"));
    assertThat(forged.status()).isEqualTo(403);
    assertThat(forged.body()).contains("signature");
    assertThat(runsOf(wf)).isEmpty();

    var sent = portal.sendWithHeaders("POST", path, event,
        Map.of("X-Portal-Signature", "sha256=" + hmac("portal-secret-1", body)));
    assertThat(sent.status()).as(sent.body()).isEqualTo(202);
    List<WorkflowRunEntity> started = runsOf(wf);
    assertThat(started).hasSize(1);
    assertThat(started.get(0).getTriggerSource()).isEqualTo("trigger:" + triggerId);

    // The same event again starts nothing more.
    portal.sendWithHeaders("POST", path, event,
        Map.of("X-Portal-Signature", "sha256=" + hmac("portal-secret-1", body)));
    assertThat(runsOf(wf)).hasSize(1);

    assertThat(portal.post("/api/webhooks/services/not-a-token", Map.of()).status()).isEqualTo(404);
    var history = api.get("/api/services/" + id + "/history").json();
    assertThat(history.toString()).contains("webhook");
  }

  @Test
  void aPollingTriggerStartsItsWorkflowForEachNewItem() {
    stub = new StubService();
    String id = service(stub.apiBaseUrl());
    var listPets = api.post("/api/services/" + id + "/apis", Map.of("name", "list_pets",
        "label", "List pets", "httpMethod", "GET", "pathTemplate", "/open/pets"));
    assertThat(listPets.ok()).as(listPets.body()).isTrue();
    var trigger = api.post("/api/services/" + id + "/triggers", Map.of("name", "new_pet",
        "label", "New pet", "apiId", listPets.json().path("id").asText(), "idField", "id"));
    assertThat(trigger.ok()).as(trigger.body()).isTrue();
    assertThat(trigger.json().path("kind").asText()).isEqualTo("polling");
    assertThat(trigger.json().path("webhookUrl").isNull()
        || trigger.json().path("webhookUrl").isMissingNode()).isTrue();
    UUID triggerId = UUID.fromString(trigger.json().path("id").asText());
    UUID wf = workflow();
    assertThat(api.put("/api/workflows/" + wf + "/service-trigger",
        Map.of("triggerId", triggerId.toString())).ok()).isTrue();
    var published = api.post("/api/services/" + id + "/publish", null);
    assertThat(published.ok()).as(published.body()).isTrue();

    stub.setPets("[{\"id\":1,\"subject\":\"Cat\"}]");
    runner.pollTrigger(UUID.fromString(id), triggerId);
    assertThat(runsOf(wf)).isEmpty();

    stub.setPets("[{\"id\":1,\"subject\":\"Cat\"},{\"id\":2,\"subject\":\"Dog\"}]");
    var found = runner.pollTrigger(UUID.fromString(id), triggerId);
    assertThat(found.newItems()).isEqualTo(1);
    assertThat(runsOf(wf)).hasSize(1);

    // Turned off, the workflow is not started by later items.
    api.put("/api/workflows/" + wf + "/enabled", Map.of("enabled", false));
    stub.setPets("[{\"id\":3,\"subject\":\"Fish\"}]");
    runner.pollTrigger(UUID.fromString(id), triggerId);
    assertThat(runsOf(wf)).hasSize(1);

    // Unknown triggers can't be picked.
    var stranger = api.put("/api/workflows/" + wf + "/service-trigger",
        Map.of("triggerId", UUID.randomUUID().toString()));
    assertThat(stranger.status()).isEqualTo(404);
    JsonNode cleared = api.put("/api/workflows/" + wf + "/service-trigger", Map.of()).json();
    assertThat(cleared.path("id").asText()).isEqualTo(wf.toString());
  }
}
