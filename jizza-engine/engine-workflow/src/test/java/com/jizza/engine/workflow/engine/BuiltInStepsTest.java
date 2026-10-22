package com.jizza.engine.workflow.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jizza.engine.api.enums.NodeType;
import com.jizza.engine.api.enums.NotificationKind;
import com.jizza.engine.api.enums.RunStatus;
import com.jizza.engine.api.enums.RunStepStatus;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.workflow.graph.WorkflowEdge;
import com.jizza.engine.api.workflow.graph.WorkflowGraph;
import com.jizza.engine.api.workflow.graph.WorkflowNode;
import com.jizza.engine.connectors.http.GuardedHttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The built-in steps that run without an agent: approval, filter, paths, set variable, storage,
 * webhook and notify.
 */
class BuiltInStepsTest {

  private static WorkflowNode node(String id, NodeType type, String configJson) {
    return new WorkflowNode(id, type, id, configJson == null ? null : Json.read(configJson), null);
  }

  private static WorkflowNode once(String id, NodeType type, String configJson) {
    return new WorkflowNode(id, type, id, configJson == null ? null : Json.read(configJson),
        new WorkflowNode.RetryPolicy(1, 1, 1.0, 10));
  }

  private static WorkflowEdge edge(String from, String to) { return new WorkflowEdge(from, to, null); }

  private static WorkflowEdge edge(String from, String to, String branch) {
    return new WorkflowEdge(from, to, branch);
  }

  private static WorkflowNode transform(String id, String expression) {
    return node(id, NodeType.TRANSFORM, "{\"expression\":\"" + expression + "\"}");
  }

  /** A straight line of nodes from a trigger to an end. */
  private static WorkflowGraph line(WorkflowNode... middle) {
    List<WorkflowNode> nodes = new ArrayList<>();
    nodes.add(node("start", NodeType.TRIGGER, null));
    nodes.addAll(List.of(middle));
    nodes.add(node("finish", NodeType.END, null));
    List<WorkflowEdge> edges = new ArrayList<>();
    for (int i = 0; i + 1 < nodes.size(); i++) edges.add(edge(nodes.get(i).id(), nodes.get(i + 1).id()));
    return new WorkflowGraph(nodes, edges);
  }

  // ── approval ────────────────────────────────────────────────────────────

  /** start → approve → (approved: pay, rejected: skip) → finish */
  private static WorkflowGraph approval(String config) {
    return new WorkflowGraph(
        List.of(node("start", NodeType.TRIGGER, null),
            node("approve", NodeType.APPROVAL, config),
            transform("pay", "'paid'"),
            transform("skip", "'skipped'"),
            node("finish", NodeType.END, null)),
        List.of(edge("start", "approve"), edge("approve", "pay", "approved"),
            edge("approve", "skip", "rejected"), edge("pay", "finish"), edge("skip", "finish")));
  }

  @Test
  void anApprovalAsksWithTheRunsDataAndTakesTheApprovedPath() {
    EngineHarness harness = new EngineHarness(approval("{\"prompt\":\"Pay Rs {{ input.amount }}?\"}"));

    var run = harness.engine.start(harness.workflow.getId(), 1, Json.obj().put("amount", 500), "manual");

    assertThat(run.status()).isEqualTo(RunStatus.PAUSED_WAITING_INSTRUCTION);
    assertThat(harness.raised).singleElement().satisfies(n -> {
      assertThat(n.kind()).isEqualTo(NotificationKind.PERMISSION_REQUEST);
      assertThat(n.body()).isEqualTo("Pay Rs 500?");
    });

    var done = harness.engine.resume(run.id(), Json.obj().put("approved", true));

    assertThat(done.status()).isEqualTo(RunStatus.DONE);
    assertThat(harness.step(run.id(), "pay").orElseThrow().getStatus()).isEqualTo(RunStepStatus.DONE);
    assertThat(harness.step(run.id(), "skip").orElseThrow().getStatus())
        .isEqualTo(RunStepStatus.SKIPPED);
    assertThat(harness.step(run.id(), "approve").orElseThrow().getOutput().path("decision").asText())
        .isEqualTo("approved");
  }

  @Test
  void aRejectionIsAnAnswerThatPicksThePathNotAFailure() {
    EngineHarness harness = new EngineHarness(approval("{\"prompt\":\"Go ahead?\"}"));
    var run = harness.engine.start(harness.workflow.getId(), 1, Json.obj(), "manual");

    var done = harness.engine.resume(run.id(), Json.obj().put("approved", false));

    assertThat(done.status()).isEqualTo(RunStatus.DONE);
    assertThat(harness.step(run.id(), "skip").orElseThrow().getStatus()).isEqualTo(RunStepStatus.DONE);
    assertThat(harness.step(run.id(), "pay").orElseThrow().getStatus())
        .isEqualTo(RunStepStatus.SKIPPED);
  }

  @Test
  void anUnansweredApprovalTakesItsTimeoutPathOnceTheDeadlinePasses() {
    EngineHarness harness = new EngineHarness(
        approval("{\"prompt\":\"Go ahead?\",\"timeout\":\"PT4H\"}"));
    var run = harness.engine.start(harness.workflow.getId(), 1, Json.obj(), "manual");

    harness.clock.advance(Duration.ofHours(1));
    assertThat(harness.engine.wake(run.id()).status())
        .as("not due yet").isEqualTo(RunStatus.PAUSED_WAITING_INSTRUCTION);

    harness.clock.advance(Duration.ofHours(4));
    var done = harness.engine.wake(run.id());

    assertThat(done.status()).isEqualTo(RunStatus.DONE);
    var output = harness.step(run.id(), "approve").orElseThrow().getOutput();
    assertThat(output.path("decision").asText()).isEqualTo("rejected");
    assertThat(output.path("timedOut").asBoolean()).isTrue();
    assertThat(harness.step(run.id(), "skip").orElseThrow().getStatus()).isEqualTo(RunStepStatus.DONE);
  }

  @Test
  void anApprovalWithNoRejectedPathEndsTheRunQuietlyWhenRejected() {
    EngineHarness harness = new EngineHarness(new WorkflowGraph(
        List.of(node("start", NodeType.TRIGGER, null),
            node("approve", NodeType.APPROVAL, "{}"),
            transform("pay", "'paid'"),
            node("finish", NodeType.END, null)),
        List.of(edge("start", "approve"), edge("approve", "pay", "approved"),
            edge("pay", "finish"))));
    var run = harness.engine.start(harness.workflow.getId(), 1, Json.obj(), "manual");

    var done = harness.engine.resume(run.id(), Json.obj().put("approved", false));

    assertThat(done.status()).isEqualTo(RunStatus.DONE);
    assertThat(harness.step(run.id(), "pay").orElseThrow().getStatus())
        .isEqualTo(RunStepStatus.SKIPPED);
  }

  // ── filter and paths ────────────────────────────────────────────────────

  @Test
  void aFilterStopsItsPathWithoutFailingTheRun() {
    WorkflowGraph graph = line(node("onlyBig", NodeType.FILTER, "{\"condition\":\"input.amount > 1000\"}"),
        transform("after", "'ran'"));

    EngineHarness small = new EngineHarness(graph);
    var skipped = small.engine.start(small.workflow.getId(), 1, Json.obj().put("amount", 50), "manual");
    assertThat(skipped.status()).isEqualTo(RunStatus.DONE);
    assertThat(small.step(skipped.id(), "after").orElseThrow().getStatus())
        .isEqualTo(RunStepStatus.SKIPPED);

    EngineHarness big = new EngineHarness(graph);
    var ran = big.engine.start(big.workflow.getId(), 1, Json.obj().put("amount", 5000), "manual");
    assertThat(big.step(ran.id(), "after").orElseThrow().getStatus()).isEqualTo(RunStepStatus.DONE);
  }

  /** start → route → (high | medium | else) → finish */
  private static WorkflowGraph paths() {
    String cases = "{\"cases\":[{\"id\":\"high\",\"label\":\"Over 1000\",\"condition\":\"input.amount > 1000\"},"
        + "{\"id\":\"medium\",\"label\":\"Over 100\",\"condition\":\"input.amount > 100\"}]}";
    return new WorkflowGraph(
        List.of(node("start", NodeType.TRIGGER, null),
            node("route", NodeType.SWITCH, cases),
            transform("escalate", "'escalated'"),
            transform("review", "'reviewed'"),
            transform("file", "'filed'"),
            node("finish", NodeType.END, null)),
        List.of(edge("start", "route"), edge("route", "escalate", "high"),
            edge("route", "review", "medium"), edge("route", "file", "else"),
            edge("escalate", "finish"), edge("review", "finish"), edge("file", "finish")));
  }

  @Test
  void pathsTakeTheFirstMatchingCaseOnly() {
    EngineHarness harness = new EngineHarness(paths());

    var run = harness.engine.start(harness.workflow.getId(), 1, Json.obj().put("amount", 5000), "manual");

    assertThat(run.status()).isEqualTo(RunStatus.DONE);
    assertThat(harness.step(run.id(), "escalate").orElseThrow().getStatus())
        .as("over 100 matches too, but the first match wins").isEqualTo(RunStepStatus.DONE);
    assertThat(harness.step(run.id(), "review").orElseThrow().getStatus())
        .isEqualTo(RunStepStatus.SKIPPED);
    assertThat(harness.step(run.id(), "file").orElseThrow().getStatus())
        .isEqualTo(RunStepStatus.SKIPPED);
  }

  @Test
  void pathsFallBackToElse() {
    EngineHarness harness = new EngineHarness(paths());

    var run = harness.engine.start(harness.workflow.getId(), 1, Json.obj().put("amount", 5), "manual");

    assertThat(harness.step(run.id(), "file").orElseThrow().getStatus()).isEqualTo(RunStepStatus.DONE);
    assertThat(harness.step(run.id(), "route").orElseThrow().getOutput().path("path").asText())
        .isEqualTo("else");
  }

  @Test
  void aPathsConnectionToACaseThatDoesNotExistIsRefused() {
    WorkflowGraph graph = new WorkflowGraph(
        List.of(node("start", NodeType.TRIGGER, null),
            node("route", NodeType.SWITCH, "{\"cases\":[{\"id\":\"a\",\"condition\":\"true\"}]}"),
            node("finish", NodeType.END, null)),
        List.of(edge("start", "route"), edge("route", "finish", "gone")));

    assertThatThrownBy(() -> new WorkflowValidator().validate(graph))
        .isInstanceOf(ValidationException.class).hasMessageContaining("gone");
  }

  @Test
  void anApprovalNeedsAnApprovedPath() {
    WorkflowGraph graph = new WorkflowGraph(
        List.of(node("start", NodeType.TRIGGER, null), node("approve", NodeType.APPROVAL, "{}"),
            node("finish", NodeType.END, null)),
        List.of(edge("start", "approve"), edge("approve", "finish", "rejected")));

    assertThatThrownBy(() -> new WorkflowValidator().validate(graph))
        .isInstanceOf(ValidationException.class).hasMessageContaining("approved");
  }

  // ── variables and storage ───────────────────────────────────────────────

  @Test
  void variablesAreSetInOrderAndReadByLaterSteps() {
    EngineHarness harness = new EngineHarness(line(
        node("vars", NodeType.SET_VARIABLE, "{\"variables\":["
            + "{\"name\":\"net\",\"expr\":\"input.amount * 0.9\"},"
            + "{\"name\":\"label\",\"expr\":\"'Net: ' + fmt:formatNumber(vars.net, 2)\"}]}"),
        transform("use", "vars.label")));

    var run = harness.engine.start(harness.workflow.getId(), 1, Json.obj().put("amount", 1000), "manual");

    assertThat(run.status()).isEqualTo(RunStatus.DONE);
    assertThat(harness.step(run.id(), "use").orElseThrow().getOutput().asText())
        .isEqualTo("Net: 900.00");
  }

  @Test
  void storageKeepsACountAcrossRuns() {
    EngineHarness harness = new EngineHarness(line(
        node("count", NodeType.STORAGE, "{\"action\":\"increment\",\"key\":\"orders:{{ input.customer }}\"}")));

    harness.engine.start(harness.workflow.getId(), 1, Json.obj().put("customer", "acme"), "manual");
    var second = harness.engine.start(harness.workflow.getId(), 1,
        Json.obj().put("customer", "acme"), "manual");

    var output = harness.step(second.id(), "count").orElseThrow().getOutput();
    assertThat(output.path("key").asText()).isEqualTo("orders:acme");
    assertThat(output.path("value").asLong()).isEqualTo(2);
    assertThat(output.path("previous").asLong()).isEqualTo(1);
  }

  @Test
  void storageAppendsKeepsTheNewestAndGetsWithADefault() {
    EngineHarness harness = new EngineHarness(line(
        node("add", NodeType.STORAGE,
            "{\"action\":\"append\",\"key\":\"seen\",\"valueExpr\":\"input.id\",\"maxItems\":2}"),
        node("missing", NodeType.STORAGE, "{\"action\":\"get\",\"key\":\"nothing\",\"default\":0}")));

    for (int id = 1; id <= 3; id++) {
      harness.engine.start(harness.workflow.getId(), 1, Json.obj().put("id", id), "manual");
    }

    assertThat(harness.stored.get("seen").getValue().toString()).isEqualTo("[2,3]");
    var last = harness.runRows.get(harness.runRows.size() - 1);
    assertThat(harness.step(last.getId(), "missing").orElseThrow().getOutput().path("value").asInt())
        .isEqualTo(0);
  }

  // ── webhook and notify ──────────────────────────────────────────────────

  @Test
  void aWebhookSendsTheRunsDataAndReturnsTheParsedResponse() {
    EngineHarness harness = new EngineHarness(line(
        node("hook", NodeType.HTTP_REQUEST, "{\"method\":\"POST\","
            + "\"url\":\"https://hooks.example.com/orders/{{ input.id }}\","
            + "\"headers\":{\"X-Source\":\"jizza-{{ input.id }}\"},"
            + "\"bodyExpr\":\"{'id': input.id, 'total': input.total}\"}"),
        transform("after", "nodes.hook.body.ok")));

    var run = harness.engine.start(harness.workflow.getId(), 1,
        Json.obj().put("id", 7).put("total", 42), "manual");

    assertThat(run.status()).isEqualTo(RunStatus.DONE);
    assertThat(harness.httpCalls).singleElement().satisfies(call -> {
      assertThat(call[0]).isEqualTo("POST");
      assertThat(call[1]).isEqualTo("https://hooks.example.com/orders/7");
      @SuppressWarnings("unchecked")
      Map<String, String> headers = (Map<String, String>) call[2];
      assertThat(headers).containsEntry("X-Source", "jizza-7")
          .containsEntry("Content-Type", "application/json");
      assertThat(Json.read((String) call[3]).path("total").asInt()).isEqualTo(42);
    });
    assertThat(harness.step(run.id(), "after").orElseThrow().getOutput().asBoolean()).isTrue();
  }

  @Test
  void aWebhookSendsMappedFieldsWithQueryParametersAndSignIn() {
    EngineHarness harness = new EngineHarness(line(
        node("hook", NodeType.HTTP_REQUEST, "{\"method\":\"POST\","
            + "\"url\":\"https://hooks.example.com/orders?src=jizza\","
            + "\"queryParams\":{\"order id\":\"{{ input.id }}\"},"
            + "\"auth\":{\"type\":\"bearer\",\"token\":\"t-{{ input.id }}\"},"
            + "\"bodyType\":\"json\",\"unflatten\":true,\"wrapInArray\":true,"
            + "\"fields\":{\"order.id\":\"{{ input.id }}\",\"order.note\":\"Total {{ input.total }}\","
            + "\"tags\":\"{{ input.tags }}\"}}")));

    var run = harness.engine.start(harness.workflow.getId(), 1,
        Json.obj().put("id", 7).put("total", 42).set("tags", Json.read("[\"a\",\"b\"]")), "manual");

    assertThat(run.status()).isEqualTo(RunStatus.DONE);
    assertThat(harness.httpCalls).singleElement().satisfies(call -> {
      assertThat(call[1]).isEqualTo("https://hooks.example.com/orders?src=jizza&order%20id=7");
      @SuppressWarnings("unchecked")
      Map<String, String> headers = (Map<String, String>) call[2];
      assertThat(headers).containsEntry("Authorization", "Bearer t-7")
          .containsEntry("Content-Type", "application/json");
      var sent = Json.read((String) call[3]);
      assertThat(sent.isArray()).isTrue();
      assertThat(sent.path(0).path("order").path("id").isNumber()).isTrue();
      assertThat(sent.path(0).path("order").path("note").asText()).isEqualTo("Total 42");
      assertThat(sent.path(0).path("tags").size()).isEqualTo(2);
    });
  }

  @Test
  void aWebhookSendsFormDataWithBasicSignInAndAnApiKeyInTheQuery() {
    EngineHarness basic = new EngineHarness(line(
        node("hook", NodeType.HTTP_REQUEST, "{\"url\":\"https://hooks.example.com\","
            + "\"auth\":{\"type\":\"basic\",\"username\":\"me\",\"password\":\"secret\"},"
            + "\"bodyType\":\"form\",\"fields\":{\"name\":\"A & B\",\"id\":\"{{ input.id }}\"}}")));
    basic.engine.start(basic.workflow.getId(), 1, Json.obj().put("id", 7), "manual");
    assertThat(basic.httpCalls).singleElement().satisfies(call -> {
      @SuppressWarnings("unchecked")
      Map<String, String> headers = (Map<String, String>) call[2];
      assertThat(headers).containsEntry("Authorization", "Basic bWU6c2VjcmV0")
          .containsEntry("Content-Type", "application/x-www-form-urlencoded");
      assertThat(call[3]).isEqualTo("name=A+%26+B&id=7");
    });

    EngineHarness key = new EngineHarness(line(
        node("hook", NodeType.HTTP_REQUEST, "{\"method\":\"GET\",\"url\":\"https://hooks.example.com/x\","
            + "\"auth\":{\"type\":\"apiKey\",\"name\":\"api_key\",\"value\":\"k1\",\"in\":\"query\"},"
            + "\"responseType\":\"text\"}")));
    var run = key.engine.start(key.workflow.getId(), 1, Json.obj(), "manual");
    assertThat(key.httpCalls).singleElement()
        .satisfies(call -> assertThat(call[1]).isEqualTo("https://hooks.example.com/x?api_key=k1"));
    assertThat(key.step(run.id(), "hook").orElseThrow().getOutput().path("body").isTextual()).isTrue();
    // The run history shows what the step was handed, but not the credentials.
    var settings = key.step(run.id(), "hook").orElseThrow().getInput().path("settings");
    assertThat(settings.path("auth").path("value").asText()).isEqualTo("(hidden)");
    assertThat(settings.path("auth").path("name").asText()).isEqualTo("api_key");
  }

  @Test
  void eachStepKeepsWhatItWasHandedForTheRunHistory() {
    EngineHarness harness = new EngineHarness(line(
        node("hook", NodeType.HTTP_REQUEST, "{\"method\":\"POST\","
            + "\"url\":\"https://hooks.example.com/orders/{{ input.id }}\","
            + "\"body\":\"{{ nope.missing }}\"}")));

    var run = harness.engine.start(harness.workflow.getId(), 1, Json.obj().put("id", 7), "manual");

    var trigger = harness.step(run.id(), "start").orElseThrow().getInput();
    assertThat(trigger.path("received").path("id").asInt()).isEqualTo(7);
    var hook = harness.step(run.id(), "hook").orElseThrow();
    assertThat(hook.getInput().path("settings").path("url").asText())
        .isEqualTo("https://hooks.example.com/orders/7");
    assertThat(hook.getInput().path("settings").path("method").asText()).isEqualTo("POST");
    assertThat(hook.getInput().path("received").has("start")).isTrue();
    // A placeholder that cannot render is kept with the reason rather than hiding the step.
    assertThat(hook.getInput().path("settings").path("body").path("template").asText())
        .isEqualTo("{{ nope.missing }}");
    assertThat(hook.getInput().path("settings").path("body").path("error").asText()).isNotBlank();
    assertThat(hook.getStartedAt()).isNotNull();
  }

  @Test
  void aWebhookThatIsRefusedFailsTheStepUnlessToldToCarryOn() {
    WorkflowGraph strict = line(once("hook", NodeType.HTTP_REQUEST,
        "{\"url\":\"https://hooks.example.com\",\"body\":\"hi\"}"));
    EngineHarness failing = new EngineHarness(strict);
    failing.nextHttpResponse = new GuardedHttpClient.Response(404, "nope", Map.of());
    var failed = failing.engine.start(failing.workflow.getId(), 1, Json.obj(), "manual");
    assertThat(failed.status()).isNotEqualTo(RunStatus.DONE);
    assertThat(failing.step(failed.id(), "hook").orElseThrow().getError()).contains("404");

    WorkflowGraph lenient = line(node("hook", NodeType.HTTP_REQUEST,
        "{\"url\":\"https://hooks.example.com\",\"failOnError\":false}"));
    EngineHarness carrying = new EngineHarness(lenient);
    carrying.nextHttpResponse = new GuardedHttpClient.Response(404, "nope", Map.of());
    var carried = carrying.engine.start(carrying.workflow.getId(), 1, Json.obj(), "manual");
    assertThat(carried.status()).isEqualTo(RunStatus.DONE);
    var output = carrying.step(carried.id(), "hook").orElseThrow().getOutput();
    assertThat(output.path("ok").asBoolean()).isFalse();
    assertThat(output.path("body").asText()).isEqualTo("nope");
  }

  @Test
  void notifyTellsOnlyTheOwnerInTheAppAndByEmail() {
    EngineHarness harness = new EngineHarness(line(node("tell", NodeType.NOTIFY,
        "{\"title\":\"Order {{ input.id }}\",\"message\":\"Total {{ input.total }}\",\"channel\":\"both\"}")));

    var run = harness.engine.start(harness.workflow.getId(), 1,
        Json.obj().put("id", 7).put("total", 42), "manual");

    assertThat(run.status()).isEqualTo(RunStatus.DONE);
    assertThat(harness.raised).singleElement().satisfies(n -> {
      assertThat(n.kind()).isEqualTo(NotificationKind.INFO);
      assertThat(n.body()).contains("Order 7").contains("Total 42");
    });
    assertThat(harness.emails).singleElement()
        .satisfies(mail -> assertThat(mail).containsExactly("owner@jizza.local", "Order 7", "Total 42"));
  }
}
