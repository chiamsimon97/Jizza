package com.jizza.app.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.enums.NodeType;
import com.jizza.engine.api.enums.WorkflowStatus;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.workflow.graph.WorkflowEdge;
import com.jizza.engine.api.workflow.graph.WorkflowGraph;
import com.jizza.engine.api.workflow.graph.WorkflowNode;
import com.jizza.engine.persistence.repository.WorkflowRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Acceptance: Functions as their own module. A person keeps functions that call each other by
 * name, tests them before saving, and a workflow's Script step runs one with inputs mapped from
 * the run; renaming a function keeps the calls to it working.
 */
@org.junit.jupiter.api.condition.EnabledIf(
    value = "com.jizza.engine.persistence.support.TestDatabase#available",
    disabledReason = "no PostgreSQL: set JIZZA_TEST_DB_URL or start Docker")
class ScriptFunctionsE2eTest extends E2eSupport {

  private static final String PASSWORD = "Correct-Horse-92";

  @LocalServerPort private int port;
  @Autowired private WorkflowRepository workflows;

  private ApiClient api;

  @BeforeEach
  void setUp() {
    api = new ApiClient(port);
    api.prime();
    String email = "fn-" + UUID.randomUUID() + "@jizza.local";
    api.post("/api/auth/register", Map.of("email", email, "password", PASSWORD));
    api.post("/api/auth/login", Map.of("email", email, "password", PASSWORD));
  }

  private static Map<String, Object> function(String name, List<Map<String, Object>> inputs, String script,
                                              String returnType) {
    Map<String, Object> f = new HashMap<>();
    f.put("name", name);
    f.put("inputs", inputs);
    f.put("script", script);
    f.put("returnType", returnType);
    return f;
  }

  @Test
  void functionsCallEachOtherAndAWorkflowRunsOne() {
    var tax = api.post("/api/functions", function("addTax",
        List.of(Map.of("name", "amount", "type", "number", "required", true)), "return amount * 1.1;", "number"));
    assertThat(tax.ok()).as(tax.body()).isTrue();
    String taxId = tax.json().path("id").asText();

    Map<String, Object> order = function("orderTotal", List.of(Map.of("name", "lines", "type", "list")), """
        var total = 0;
        for (var line : lines) { total = total + line.price; }
        return {'subtotal': total, 'total': fn.addTax(total).round(2)};
        """, "map");
    order.put("returnFields", List.of(Map.of("path", "subtotal", "type", "number"), Map.of("path", "total", "type", "number")));

    // Tested before it is saved.
    var tested = api.post("/api/functions/test", Map.of("function", order, "args",
        Map.of("lines", List.of(Map.of("price", 10), Map.of("price", 30)))));
    assertThat(tested.json().path("error").isNull()).as(tested.body()).isTrue();
    assertThat(tested.json().path("value").path("total").asDouble()).isEqualTo(44.0);

    var saved = api.post("/api/functions", order);
    assertThat(saved.ok()).as(saved.body()).isTrue();
    String orderId = saved.json().path("id").asText();
    assertThat(api.post("/api/functions", order).status()).isEqualTo(409);

    // Renaming addTax rewrites the call in orderTotal.
    var renamed = api.put("/api/functions/" + taxId, function("withTax",
        List.of(Map.of("name", "amount", "type", "number", "required", true)), "return amount * 1.1;", "number"));
    assertThat(renamed.ok()).as(renamed.body()).isTrue();
    assertThat(api.get("/api/functions/" + orderId).json().path("script").asText()).contains("fn.withTax(total)");

    UUID workflowId = approvedWorkflow(Json.obj().put("functionId", orderId)
        .set("inputs", Json.obj().put("lines", "{{ input.lines }}")));
    var run = api.post("/api/workflows/" + workflowId + "/run",
        Map.of("version", 1, "input", Map.of("lines", List.of(Map.of("price", 5), Map.of("price", 15)))));
    assertThat(run.json().path("status").asText()).as(run.body()).isEqualTo("DONE");
    JsonNode output = null;
    for (JsonNode step : run.json().path("steps")) if (step.path("nodeId").asText().equals("calc")) output = step.path("output");
    assertThat(output).as(run.body()).isNotNull();
    assertThat(output.path("total").asDouble()).isEqualTo(22.0);

    assertThat(api.get("/api/functions/" + orderId).json().path("usedBy").get(0).asText()).isEqualTo("Order maths");
  }

  @Test
  void aFunctionIsItsOwnersAlone() {
    var created = api.post("/api/functions", function("double",
        List.of(Map.of("name", "n", "type", "number")), "n * 2", "number"));
    assertThat(created.ok()).as(created.body()).isTrue();
    String id = created.json().path("id").asText();

    var other = new ApiClient(port);
    other.prime();
    String email = "fn-other-" + UUID.randomUUID() + "@jizza.local";
    other.post("/api/auth/register", Map.of("email", email, "password", PASSWORD));
    other.post("/api/auth/login", Map.of("email", email, "password", PASSWORD));
    assertThat(other.get("/api/functions/" + id).status()).isEqualTo(404);
    assertThat(other.get("/api/functions").json().findValuesAsText("id")).doesNotContain(id);
    assertThat(other.delete("/api/functions/" + id).status()).isEqualTo(404);

    assertThat(api.delete("/api/functions/" + id).ok()).isTrue();
    assertThat(api.get("/api/functions/" + id).status()).isEqualTo(404);
  }

  private UUID approvedWorkflow(JsonNode config) {
    WorkflowGraph graph = new WorkflowGraph(
        List.of(new WorkflowNode("start", NodeType.TRIGGER, "start", null, null),
            new WorkflowNode("calc", NodeType.SCRIPT, "calc", config, new WorkflowNode.RetryPolicy(1, 1, 1.0, 50)),
            new WorkflowNode("finish", NodeType.END, "finish", null, null)),
        List.of(new WorkflowEdge("start", "calc", null), new WorkflowEdge("calc", "finish", null)));
    var saved = api.post("/api/workflows", Map.of("name", "Order maths", "graph", graph.toJson()));
    assertThat(saved.ok()).as(saved.body()).isTrue();
    UUID id = UUID.fromString(saved.json().path("id").asText());
    var entity = workflows.findById(id).orElseThrow();
    entity.setStatus(WorkflowStatus.APPROVED);
    workflows.save(entity);
    return id;
  }
}
