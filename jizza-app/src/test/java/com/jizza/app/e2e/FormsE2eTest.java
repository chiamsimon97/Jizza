package com.jizza.app.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.enums.NodeType;
import com.jizza.engine.api.enums.RunStatus;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.workflow.graph.WorkflowEdge;
import com.jizza.engine.api.workflow.graph.WorkflowGraph;
import com.jizza.engine.api.workflow.graph.WorkflowNode;
import com.jizza.engine.persistence.repository.FormSubmissionRepository;
import com.jizza.engine.persistence.repository.WorkflowRunRepository;
import com.jizza.engine.workflow.forms.DefaultForms;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.domain.PageRequest;

/**
 * Forms end to end: build one from a workflow's steps, publish it, send it without an account,
 * and see the workflow start only while it is on, with the step the form fills set from the
 * answers.
 */
@org.junit.jupiter.api.condition.EnabledIf(
    value = "com.jizza.engine.persistence.support.TestDatabase#available",
    disabledReason = "no PostgreSQL: set JIZZA_TEST_DB_URL or start Docker")
class FormsE2eTest extends E2eSupport {

  private static final String PASSWORD = "Correct-Horse-92";

  @LocalServerPort private int port;
  @Autowired private DefaultForms forms;
  @Autowired private FormSubmissionRepository submissions;
  @Autowired private WorkflowRunRepository runs;

  private ApiClient api;

  @BeforeEach
  void setUp() {
    api = signedIn();
  }

  private ApiClient signedIn() {
    ApiClient client = new ApiClient(port);
    client.prime();
    String email = "forms-" + UUID.randomUUID() + "@jizza.local";
    client.post("/api/auth/register", Map.of("email", email, "password", PASSWORD));
    client.post("/api/auth/login", Map.of("email", email, "password", PASSWORD));
    return client;
  }

  private UUID workflow() {
    var graph = new WorkflowGraph(List.of(
        new WorkflowNode("start", NodeType.TRIGGER, "Start", Json.read(
            "{\"inputs\":[{\"name\":\"email\",\"type\":\"text\",\"required\":true}]}"), null),
        new WorkflowNode("tell", NodeType.NOTIFY, "Tell me", Json.obj()
            .put("title", "Fixed title").put("message", "New request from {{ input.email }}")
            .put("channel", "app"), null),
        new WorkflowNode("end", NodeType.END, "Done", Json.obj(), null)),
        List.of(new WorkflowEdge("start", "tell", null), new WorkflowEdge("tell", "end", null)));
    var saved = api.post("/api/workflows", Map.of("name", "Requests", "graph", graph.toJson()));
    assertThat(saved.ok()).as(saved.body()).isTrue();
    return UUID.fromString(saved.json().path("id").asText());
  }

  private JsonNode fields(UUID workflowId) {
    return Json.read(("""
        [{"id":"l1","type":"step_layout","label":"Start","workflowId":"W","nodeId":"start","children":[
           {"id":"a","type":"email","name":"email","label":"Email","required":true,
            "binding":{"workflowId":"W","nodeId":"start","path":[],"mode":"input"}}]},
         {"id":"l2","type":"step_layout","label":"Tell me","workflowId":"W","nodeId":"tell","children":[
           {"id":"b","type":"single_line","name":"subject","label":"Subject",
            "binding":{"workflowId":"W","nodeId":"tell","path":["title"],"mode":"template"}},
           {"id":"c","type":"multi_line","name":"later","label":"Later","pending":true,
            "binding":{"workflowId":"W","nodeId":"tell","path":["message"],"mode":"template"}}]},
         {"id":"n","type":"auto_number","name":"ticket","label":"Ticket","startAt":100}]
        """).replace("W", workflowId.toString()));
  }

  private UUID latestSubmission(UUID formId) {
    return submissions.findByFormIdOrderBySubmittedAtDesc(formId, PageRequest.of(0, 1)).get(0).getId();
  }

  @Test
  void aPublishedFormStartsItsWorkflowOnlyWhileItIsOn() {
    UUID wf = workflow();
    var created = api.post("/api/forms", Map.of("name", "Request access", "fields", fields(wf),
        "settings", Json.obj(), "sourceWorkflowId", wf.toString()));
    assertThat(created.ok()).as(created.body()).isTrue();
    UUID formId = UUID.fromString(created.json().path("id").asText());
    assertThat(api.put("/api/forms/" + formId + "/workflows", Map.of("workflowIds", List.of(wf))).ok()).isTrue();

    var published = api.post("/api/forms/" + formId + "/publish", null);
    assertThat(published.ok()).as(published.body()).isTrue();
    String token = published.json().path("publicToken").asText();

    // A stranger with the link sees the accepted fields only, and nothing of the workflow.
    ApiClient stranger = new ApiClient(port);
    stranger.prime();
    var shown = stranger.get("/api/public/forms/" + token);
    assertThat(shown.ok()).as(shown.body()).isTrue();
    assertThat(shown.body()).doesNotContain("binding").doesNotContain(wf.toString()).doesNotContain("\"later\"");

    var missing = stranger.post("/api/public/forms/" + token + "/submit", Map.of("subject", "Hi"));
    assertThat(missing.status()).isEqualTo(400);
    assertThat(missing.body()).contains("Email is required");

    // Off: kept, but nothing starts.
    var sent = stranger.post("/api/public/forms/" + token + "/submit",
        Map.of("email", "ann@example.com", "subject", "Please add Ann"));
    assertThat(sent.ok()).as(sent.body()).isTrue();
    assertThat(forms.startWorkflows(latestSubmission(formId))).isZero();

    // On: the submission starts it, with the step the form fills pointed at the answer.
    var on = api.put("/api/workflows/" + wf + "/enabled", Map.of("enabled", true));
    assertThat(on.json().path("enabled").asBoolean()).isTrue();
    assertThat(on.json().path("formId").asText()).isEqualTo(formId.toString());
    sent = stranger.post("/api/public/forms/" + token + "/submit",
        Map.of("email", "bo@example.com", "subject", "Please add {{ input.email }} Bo"));
    assertThat(sent.json().path("number").asInt()).isEqualTo(2);
    UUID submissionId = latestSubmission(formId);
    assertThat(forms.startWorkflows(submissionId)).isEqualTo(1);

    var submission = submissions.findById(submissionId).orElseThrow();
    assertThat(submission.getData().path("ticket").asInt()).isEqualTo(101);
    UUID runId = UUID.fromString(submission.getRuns().get(0).path("runId").asText());
    var run = api.get("/api/runs/" + runId).json();
    assertThat(run.path("status").asText()).isEqualTo(RunStatus.DONE.name());
    assertThat(run.path("triggerSource").asText()).isEqualTo("form:" + formId);
    JsonNode told = null;
    for (JsonNode step : run.path("steps")) if (step.path("nodeId").asText().equals("tell")) told = step.path("output");
    assertThat(told).isNotNull();
    // What a stranger typed is data: a {{ }} in it is not read as a formula.
    assertThat(told.path("title").asText()).isEqualTo("Please add {{ input.email }} Bo");
    assertThat(told.path("message").asText()).isEqualTo("New request from bo@example.com");

    // Saving new steps in the builder keeps it on; turning it off stops the form starting it.
    var graph = api.get("/api/workflows/" + wf).json().path("graph");
    ((com.fasterxml.jackson.databind.node.ObjectNode) graph.path("nodes").get(1).path("config")).put("message", "Changed");
    var resaved = api.post("/api/workflows", Map.of("id", wf.toString(), "name", "Requests", "graph", graph));
    assertThat(resaved.json().path("enabled").asBoolean()).as(resaved.body()).isTrue();
    assertThat(api.put("/api/workflows/" + wf + "/enabled", Map.of("enabled", false)).json().path("enabled").asBoolean()).isFalse();
    stranger.post("/api/public/forms/" + token + "/submit", Map.of("email", "cy@example.com"));
    assertThat(forms.startWorkflows(latestSubmission(formId))).isZero();
    // Off still lets the person test it by hand.
    assertThat(api.post("/api/workflows/" + wf + "/run", Map.of("input", Map.of("email", "me@example.com"))).ok()).isTrue();

    // The owner sees the submissions; someone else sees nothing.
    var list = api.get("/api/forms/" + formId + "/submissions");
    assertThat(list.json()).hasSize(3);
    assertThat(signedIn().get("/api/forms/" + formId).status()).isEqualTo(404);

    // Unpublished, the link shows nothing.
    api.post("/api/forms/" + formId + "/unpublish", null);
    assertThat(stranger.get("/api/public/forms/" + token).status()).isEqualTo(404);
    assertThat(runs.findById(runId)).isPresent();
  }
}
