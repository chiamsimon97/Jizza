package com.jizza.engine.workflow.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jizza.engine.api.enums.NodeType;
import com.jizza.engine.api.enums.RunStatus;
import com.jizza.engine.api.enums.RunStepStatus;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.workflow.graph.WorkflowEdge;
import com.jizza.engine.api.workflow.graph.WorkflowGraph;
import com.jizza.engine.api.workflow.graph.WorkflowNode;
import com.jizza.engine.persistence.entity.WorkflowRunEntity;
import com.jizza.engine.persistence.entity.WorkflowRunStepEntity;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WebAutomationWorkflowTest {

  private static WorkflowNode node(String id, NodeType type, String config) {
    return new WorkflowNode(id, type, id, config == null ? null : Json.read(config), null);
  }

  private static WorkflowNode once(String id, String config) {
    return new WorkflowNode(id, NodeType.DEVICE_ACTION, id, Json.read(config),
        new WorkflowNode.RetryPolicy(1, 1, 1, 10));
  }

  private static WorkflowEdge edge(String from, String to) {
    return new WorkflowEdge(from, to, null);
  }

  private static WorkflowGraph line(WorkflowNode... middle) {
    java.util.ArrayList<WorkflowNode> nodes = new java.util.ArrayList<>();
    nodes.add(node("start", NodeType.TRIGGER, null));
    nodes.addAll(List.of(middle));
    nodes.add(node("finish", NodeType.END, null));
    java.util.ArrayList<WorkflowEdge> edges = new java.util.ArrayList<>();
    for (int i = 0; i < nodes.size() - 1; i++) {
      edges.add(edge(nodes.get(i).id(), nodes.get(i + 1).id()));
    }
    return new WorkflowGraph(nodes, edges);
  }

  @Test
  void sameBrowserStepsUseTheOpenedDeviceAndSessionAndCleanupAtTheEnd() {
    EngineHarness harness = new EngineHarness(line(
        node("open", NodeType.DEVICE_ACTION,
            """
            {"preset":"web","action":"browser.open","target":"sameBrowser",
             "inputs":{"url":"https://example.test"}}
            """),
        node("info", NodeType.DEVICE_ACTION,
            """
            {"preset":"web","action":"browser.pageInfo","target":"sameBrowser","inputs":{}}
            """)));
    harness.nextDeviceOutput = Json.obj().put("sessionId", "s_one");

    var run = harness.engine.start(harness.workflow.getId(), 1, Json.obj(), "manual");

    assertThat(run.status()).isEqualTo(RunStatus.DONE);
    var open = harness.deviceRequests.get(0);
    var info = harness.deviceRequests.get(1);
    assertThat(info.target().deviceId()).isEqualTo(open.target().deviceId() == null
        ? UUID.nameUUIDFromBytes("browser-device".getBytes(java.nio.charset.StandardCharsets.UTF_8))
        : open.target().deviceId());
    assertThat(info.input().path("session").asText()).isEqualTo("s_one");
    assertThat(harness.deviceRequests.get(2).action()).isEqualTo("browser.close");
    assertThat(harness.deviceRequests.get(2).runId()).isNull();
  }

  @Test
  void browserAffinityCanBeRestoredFromACheckpointOutput() {
    EngineHarness harness = new EngineHarness(line(
        node("open", NodeType.DEVICE_ACTION,
            "{\"preset\":\"web\",\"action\":\"browser.open\",\"target\":\"sameBrowser\","
                + "\"inputs\":{\"url\":\"https://example.test\"}}"),
        node("info", NodeType.DEVICE_ACTION,
            "{\"preset\":\"web\",\"action\":\"browser.pageInfo\",\"target\":\"sameBrowser\",\"inputs\":{}}")));
    UUID device = UUID.randomUUID();
    RunContext context = RunContext.start(Json.obj());
    context.startFrontier("open", false);
    WorkflowRunEntity run = new WorkflowRunEntity();
    run.setUserId(harness.userId);
    run.setWorkflowId(harness.workflow.getId());
    run.setVersion(1);
    run.setTriggerSource("manual");
    run.setStatus(RunStatus.RUNNING);
    run.setCurrentNode("open");
    run.setContext(context.toJson());
    harness.runs.save(run);

    WorkflowRunStepEntity checkpoint = new WorkflowRunStepEntity();
    checkpoint.setRunId(run.getId());
    checkpoint.setNodeId("open");
    checkpoint.setStatus(RunStepStatus.DONE);
    checkpoint.setAttempts(1);
    checkpoint.setStartedAt(Instant.now());
    checkpoint.setFinishedAt(Instant.now());
    checkpoint.setOutput(Json.obj().put("_browserAction", "browser.open")
        .set("_browser", Json.obj().put("deviceId", device.toString())
            .put("sessionId", "s_checkpoint")));
    harness.steps.save(checkpoint);
    harness.nextDeviceOutput = Json.obj();

    harness.engine.resumeAfterRestart(run.getId());

    var info = harness.deviceRequests.stream()
        .filter(r -> "info".equals(r.nodeId())).findFirst().orElseThrow();
    assertThat(info.target().deviceId()).isEqualTo(device);
    assertThat(info.input().path("session").asText()).isEqualTo("s_checkpoint");
  }

  @Test
  void keepBrowserOpenOnStartSuppressesTerminalCleanup() {
    WorkflowNode start = node("start", NodeType.TRIGGER, "{\"keepBrowserOpen\":true}");
    WorkflowNode open = node("open", NodeType.DEVICE_ACTION,
        "{\"preset\":\"web\",\"action\":\"browser.open\",\"target\":\"sameBrowser\","
            + "\"inputs\":{\"url\":\"https://example.test\"}}");
    WorkflowNode finish = node("finish", NodeType.END, null);
    EngineHarness harness = new EngineHarness(new WorkflowGraph(
        List.of(start, open, finish), List.of(edge("start", "open"), edge("open", "finish"))));
    harness.nextDeviceOutput = Json.obj().put("sessionId", "s_keep");

    harness.engine.start(harness.workflow.getId(), 1, Json.obj(), "manual");

    assertThat(harness.deviceRequests).extracting(r -> r.action())
        .containsExactly("browser.open");
  }

  @Test
  void explicitAndAnyTargetsDoNotReuseSameBrowserAffinity() {
    UUID affinityDevice = UUID.randomUUID();
    UUID explicitDevice = UUID.randomUUID();
    for (String target : List.of(explicitDevice.toString(), "any")) {
      EngineHarness harness = new EngineHarness(line(node("info", NodeType.DEVICE_ACTION,
          "{\"preset\":\"web\",\"action\":\"browser.pageInfo\",\"target\":\"" + target
              + "\",\"inputs\":{}}")));
      RunContext context = RunContext.start(Json.obj());
      context.startFrontier("start", false);
      context.setBrowser(new RunContext.Browser(affinityDevice, "s_affinity", false));
      WorkflowRunEntity run = new WorkflowRunEntity();
      run.setUserId(harness.userId);
      run.setWorkflowId(harness.workflow.getId());
      run.setVersion(1);
      run.setTriggerSource("manual");
      run.setStatus(RunStatus.RUNNING);
      run.setCurrentNode("start");
      run.setContext(context.toJson());
      harness.runs.save(run);

      harness.engine.resumeAfterRestart(run.getId());

      var request = harness.deviceRequests.stream()
          .filter(r -> "info".equals(r.nodeId())).findFirst().orElseThrow();
      assertThat(request.input().has("session")).isFalse();
      if ("any".equals(target)) {
        assertThat(request.target().capability()).isEqualTo("browser");
      } else {
        assertThat(request.target().deviceId()).isEqualTo(explicitDevice);
      }
    }
  }

  @Test
  void explicitExistingSessionIsNotOwnedByTheWorkflow() {
    UUID device = UUID.randomUUID();
    EngineHarness harness = new EngineHarness(line(node("open", NodeType.DEVICE_ACTION,
        "{\"preset\":\"web\",\"action\":\"browser.open\",\"target\":\"" + device
            + "\",\"inputs\":{\"url\":\"https://example.test\",\"session\":\"s_external\"}}")));
    harness.nextDeviceOutput = Json.obj().put("sessionId", "s_external");

    harness.engine.start(harness.workflow.getId(), 1, Json.obj(), "manual");

    assertThat(harness.deviceRequests).extracting(r -> r.action())
        .containsExactly("browser.open");
  }

  @Test
  void defaultSessionCloseClearsAffinityButTabCloseStillRunsCleanup() {
    EngineHarness sessionClose = new EngineHarness(line(
        node("open", NodeType.DEVICE_ACTION,
            "{\"preset\":\"web\",\"action\":\"browser.open\",\"target\":\"sameBrowser\","
                + "\"inputs\":{\"url\":\"https://example.test\"}}"),
        node("close", NodeType.DEVICE_ACTION,
            "{\"preset\":\"web\",\"action\":\"browser.close\",\"target\":\"sameBrowser\","
                + "\"inputs\":{}}")));
    sessionClose.deviceOutputs.put("browser.open", Json.obj().put("sessionId", "s_close"));

    sessionClose.engine.start(sessionClose.workflow.getId(), 1, Json.obj(), "manual");

    assertThat(sessionClose.deviceRequests).extracting(r -> r.action())
        .containsExactly("browser.open", "browser.close");

    EngineHarness tabClose = new EngineHarness(line(
        node("open", NodeType.DEVICE_ACTION,
            "{\"preset\":\"web\",\"action\":\"browser.open\",\"target\":\"sameBrowser\","
                + "\"inputs\":{\"url\":\"https://example.test\"}}"),
        node("close", NodeType.DEVICE_ACTION,
            "{\"preset\":\"web\",\"action\":\"browser.close\",\"target\":\"sameBrowser\","
                + "\"inputs\":{\"what\":\"tab\"}}")));
    tabClose.deviceOutputs.put("browser.open", Json.obj().put("sessionId", "s_tab"));

    tabClose.engine.start(tabClose.workflow.getId(), 1, Json.obj(), "manual");

    assertThat(tabClose.deviceRequests).extracting(r -> r.action())
        .containsExactly("browser.open", "browser.close", "browser.close");
    assertThat(tabClose.deviceRequests.get(2).input().path("what").asText()).isEqualTo("session");
  }

  @Test
  void cancellationClosesAnOwnedBrowserExactlyOnce() {
    EngineHarness harness = new EngineHarness(line());
    RunContext context = RunContext.start(Json.obj());
    UUID workspaceId = UUID.randomUUID();
    context.setBrowser(new RunContext.Browser(
        UUID.randomUUID(), workspaceId, "s_cancel", true));
    WorkflowRunEntity run = new WorkflowRunEntity();
    run.setUserId(harness.userId);
    run.setWorkflowId(harness.workflow.getId());
    run.setVersion(1);
    run.setStatus(RunStatus.RUNNING);
    run.setContext(context.toJson());
    harness.runs.save(run);

    harness.engine.cancel(run.getId());
    harness.engine.cancel(run.getId());

    assertThat(harness.deviceRequests).hasSize(1);
    assertThat(harness.deviceRequests.get(0).action()).isEqualTo("browser.close");
    assertThat(harness.deviceRequests.get(0).runId()).isNull();
    assertThat(harness.deviceRequests.get(0).workspaceId()).isEqualTo(workspaceId);
  }

  @Test
  void cancellationClosesEveryOwnedBrowserSession() {
    EngineHarness harness = new EngineHarness(line());
    RunContext context = RunContext.start(Json.obj());
    context.rememberOwnedBrowser(new RunContext.Browser(UUID.randomUUID(), "s_first", true));
    context.setBrowser(new RunContext.Browser(UUID.randomUUID(), "s_second", true));
    WorkflowRunEntity run = new WorkflowRunEntity();
    run.setUserId(harness.userId);
    run.setWorkflowId(harness.workflow.getId());
    run.setVersion(1);
    run.setStatus(RunStatus.RUNNING);
    run.setContext(context.toJson());
    harness.runs.save(run);

    harness.engine.cancel(run.getId());

    assertThat(harness.deviceRequests).extracting(r -> r.input().path("session").asText())
        .containsExactlyInAnyOrder("s_first", "s_second");
  }

  @Test
  void failureClosesAnOwnedBrowser() {
    EngineHarness harness = new EngineHarness(line(once("fail",
        "{\"preset\":\"web\",\"action\":\"browser.pageInfo\",\"target\":\"sameBrowser\","
            + "\"inputs\":{},\"onFailure\":\"dead_letter\"}")));
    harness.nextDeviceStatus = com.jizza.engine.api.device.DeviceCommandStatus.FAILED;
    RunContext context = RunContext.start(Json.obj());
    context.startFrontier("fail", false);
    context.setBrowser(new RunContext.Browser(UUID.randomUUID(), "s_failure", true));
    WorkflowRunEntity run = new WorkflowRunEntity();
    run.setUserId(harness.userId);
    run.setWorkflowId(harness.workflow.getId());
    run.setVersion(1);
    run.setTriggerSource("manual");
    run.setStatus(RunStatus.RUNNING);
    run.setCurrentNode("fail");
    run.setContext(context.toJson());
    harness.runs.save(run);

    var failed = harness.engine.resumeAfterRestart(run.getId());

    assertThat(failed.status()).isEqualTo(RunStatus.FAILED);
    assertThat(harness.deviceRequests).extracting(r -> r.action())
        .containsExactly("browser.pageInfo", "browser.close");
  }

  @Test
  void permanentBrowserFailureDoesNotRetryButPageCrashDoes() {
    String config = """
        {"preset":"web","action":"browser.pageInfo","target":"sameBrowser","inputs":{},
         "onFailure":"dead_letter"}
        """;
    EngineHarness permanent = new EngineHarness(line(once("web", config)));
    permanent.nextDeviceStatus = com.jizza.engine.api.device.DeviceCommandStatus.FAILED;
    permanent.nextDeviceError = "Could not find the element";
    permanent.nextDeviceOutput = Json.obj().set("error",
        Json.obj().put("kind", "element_not_found").put("url", "https://example.test"));
    var failed = permanent.engine.start(
        permanent.workflow.getId(), 1, Json.obj(), "manual");
    assertThat(failed.status()).isEqualTo(RunStatus.FAILED);
    assertThat(permanent.deviceRequests).hasSize(1);
    assertThat(permanent.run(failed.id()).getContext().path("state").path("web")
        .path("error").path("kind").asText()).isEqualTo("element_not_found");

    WorkflowNode retrying = new WorkflowNode("web", NodeType.DEVICE_ACTION, "web",
        Json.read(config), new WorkflowNode.RetryPolicy(2, 1, 1, 10));
    EngineHarness retryable = new EngineHarness(line(retrying));
    retryable.nextDeviceStatus = com.jizza.engine.api.device.DeviceCommandStatus.FAILED;
    retryable.nextDeviceOutput = Json.obj().set("error",
        Json.obj().put("kind", "page_crashed"));
    var retried = retryable.engine.start(
        retryable.workflow.getId(), 1, Json.obj(), "manual");
    assertThat(retried.status()).isEqualTo(RunStatus.FAILED);
    assertThat(retryable.deviceRequests).hasSize(2);
  }

  @Test
  void childRunsInheritBrowserUnlessTheyRequestANewSession() {
    assertChildInheritance(NodeType.SUB_WORKFLOW, false, true);
    assertChildInheritance(NodeType.SUB_WORKFLOW, true, false);
    assertChildInheritance(NodeType.FOR_EACH, false, true);
    assertChildInheritance(NodeType.FOR_EACH, true, false);
  }

  private void assertChildInheritance(NodeType childType, boolean newSession, boolean inherited) {
    EngineHarness harness = new EngineHarness(line());
    var child = harness.addWorkflow("child", line(
        node("childOpen", NodeType.DEVICE_ACTION,
            "{\"preset\":\"web\",\"action\":\"browser.open\",\"target\":\"sameBrowser\","
                + "\"inputs\":{\"url\":\"https://child.example.test\"}}"),
        node("inspect", NodeType.DEVICE_ACTION,
            "{\"preset\":\"web\",\"action\":\"browser.pageInfo\",\"target\":\"sameBrowser\","
                + "\"inputs\":{}}")));
    String childConfig = "{\"workflowId\":\"" + child.getId()
        + "\",\"newBrowserSession\":" + newSession
        + (childType == NodeType.FOR_EACH ? ",\"items\":\"[1]\"" : "") + "}";
    harness.workflow.setGraph(line(
        node("parentOpen", NodeType.DEVICE_ACTION,
            "{\"preset\":\"web\",\"action\":\"browser.open\",\"target\":\"sameBrowser\","
                + "\"inputs\":{\"url\":\"https://example.test\"}}"),
        node("child", childType, childConfig)).toJson());
    harness.nextDeviceOutput = Json.obj().put("sessionId", "s_parent");

    harness.engine.start(harness.workflow.getId(), 1, Json.obj(), "manual");

    var open = harness.deviceRequests.stream()
        .filter(r -> "childOpen".equals(r.nodeId())).findFirst().orElseThrow();
    assertThat(open.input().path("in").asText()).isEqualTo(newSession ? "newSession" : "");
    assertThat(open.input().has("session")).isEqualTo(inherited);
    var request = harness.deviceRequests.stream()
        .filter(r -> "inspect".equals(r.nodeId())).findFirst().orElseThrow();
    assertThat(request.input().path("session").asText()).isEqualTo("s_parent");
  }

  @Test
  void webValidatorRequiresCatalogActionsAndElementLocators() {
    EngineHarness harness = new EngineHarness(line());
    WorkflowValidator validator = new WorkflowValidator();
    // The harness's production-shaped validator is exercised when starting this workflow.
    harness.workflow.setGraph(line(node("click", NodeType.DEVICE_ACTION,
        "{\"preset\":\"web\",\"action\":\"browser.click\",\"inputs\":{}}")).toJson());
    assertThatThrownBy(() -> harness.engine.start(
        harness.workflow.getId(), 1, Json.obj(), "manual"))
        .isInstanceOf(ValidationException.class)
        .hasMessage("Pick the element this step works on.");

    assertThatThrownBy(() -> validator.validate(line(node("bad", NodeType.DEVICE_ACTION,
        "{\"preset\":\"web\",\"action\":\"device.tap\",\"inputs\":{}}"))))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("available browser action");

    harness.workflow.setGraph(line(node("mixed", NodeType.DEVICE_ACTION,
        "{\"preset\":\"web\",\"action\":\"browser.click\","
            + "\"inputs\":{\"element\":\"prefix {{ input.element }}\"}}")).toJson());
    assertThatThrownBy(() -> harness.engine.start(
        harness.workflow.getId(), 1, Json.obj(), "manual"))
        .isInstanceOf(ValidationException.class)
        .hasMessage("Pick the element this step works on.");

    EngineHarness missingInput = new EngineHarness(line(node("open", NodeType.DEVICE_ACTION,
        "{\"preset\":\"web\",\"action\":\"browser.open\",\"inputs\":{}}")));
    assertThatThrownBy(() -> missingInput.engine.start(
        missingInput.workflow.getId(), 1, Json.obj(), "manual"))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("needs the input 'url'");
  }

  // ---- W5: locator fixes and fast replay ----------------------------------------------------

  private static final String OPEN = """
      {"preset":"web","action":"browser.open","version":2,"target":"sameBrowser",
       "inputs":{"url":"https://example.test"}}
      """;
  private static final String READ = """
      {"preset":"web","action":"browser.getText","version":1,"target":"sameBrowser",
       "inputs":{"element":{"strategies":[{"by":"text","value":"Total"}]}}}
      """;

  @Test
  void aStepThatLostItsElementHandsTheCandidatesOverForAFix() {
    EngineHarness harness = new EngineHarness(line(once("read", READ)));
    harness.nextDeviceStatus = com.jizza.engine.api.device.DeviceCommandStatus.FAILED;
    com.fasterxml.jackson.databind.node.ObjectNode error = Json.obj()
        .put("kind", "element_not_found").put("url", "https://example.test");
    error.putArray("candidates").addObject().put("score", 0.9).put("text", "Grand total")
        .putObject("element").putArray("strategies").addObject().put("by", "text")
        .put("value", "Grand total");
    harness.nextDeviceOutput = Json.obj().set("error", error);

    var run = harness.engine.start(harness.workflow.getId(), 1, Json.obj(), "manual");

    assertThat(run.status()).isNotEqualTo(RunStatus.DONE);
    assertThat(harness.proposedFixes).hasSize(1);
    var fix = harness.proposedFixes.get(0);
    assertThat(fix.nodeId()).isEqualTo("read");
    assertThat(fix.workflowId()).isEqualTo(harness.workflow.getId());
    assertThat(fix.runId()).isEqualTo(run.id());
    assertThat(fix.error().path("candidates")).hasSize(1);
  }

  @Test
  void aLostElementWithoutCandidatesOffersNoFix() {
    EngineHarness harness = new EngineHarness(line(once("read", READ)));
    harness.nextDeviceStatus = com.jizza.engine.api.device.DeviceCommandStatus.FAILED;
    harness.nextDeviceOutput = Json.obj().set("error", Json.obj().put("kind", "element_not_found"));

    harness.engine.start(harness.workflow.getId(), 1, Json.obj(), "manual");

    assertThat(harness.proposedFixes).isEmpty();
  }

  @Test
  void fastReplayIsOffByDefault() {
    EngineHarness harness = new EngineHarness(line(node("open", NodeType.DEVICE_ACTION, OPEN),
        node("read", NodeType.DEVICE_ACTION, READ)));
    harness.nextDeviceOutput = Json.obj().put("sessionId", "s_one");

    harness.engine.start(harness.workflow.getId(), 1, Json.obj(), "manual");

    assertThat(harness.deviceRequests).extracting(r -> r.action())
        .containsExactly("browser.open", "browser.getText", "browser.close");
  }

  @Test
  void fastReplaySendsOneBatchAndMapsEachStepsOutput() {
    EngineHarness harness = new EngineHarness(line(node("open", NodeType.DEVICE_ACTION, OPEN),
        node("read", NodeType.DEVICE_ACTION, READ)));
    harness.deviceActions.setFastReplay(true);
    com.fasterxml.jackson.databind.node.ObjectNode batch = Json.obj();
    batch.putObject("steps").set("open", Json.obj().put("sessionId", "s_batch")
        .put("url", "https://example.test"));
    ((com.fasterxml.jackson.databind.node.ObjectNode) batch.get("steps"))
        .set("read", Json.obj().put("text", "Total: 12"));
    harness.deviceOutputs.put("browser.runSteps", batch);

    var run = harness.engine.start(harness.workflow.getId(), 1, Json.obj(), "manual");

    assertThat(run.status()).isEqualTo(RunStatus.DONE);
    assertThat(harness.deviceRequests).extracting(r -> r.action())
        .containsExactly("browser.runSteps", "browser.close");
    var sent = harness.deviceRequests.get(0).input();
    assertThat(sent.path("steps")).hasSize(2);
    assertThat(sent.path("steps").get(0).path("key").asText()).isEqualTo("open");
    assertThat(sent.path("steps").get(1).path("input").path("element").path("strategies"))
        .hasSize(1);
    var context = harness.run(run.id()).getContext();
    assertThat(context.path("nodes").path("read").path("text").asText()).isEqualTo("Total: 12");
    assertThat(context.path("nodes").path("open").path("sessionId").asText()).isEqualTo("s_batch");
    // The session the batch opened is the run's browser, closed at the end as usual.
    assertThat(harness.deviceRequests.get(1).input().path("session").asText()).isEqualTo("s_batch");
  }

  @Test
  void fastReplayFallsBackToStepByStepWhenTheBrowserLacksTheAction() {
    EngineHarness harness = new EngineHarness(line(node("open", NodeType.DEVICE_ACTION, OPEN),
        node("read", NodeType.DEVICE_ACTION, READ)));
    harness.deviceActions.setFastReplay(true);
    harness.unsupportedDeviceActions.add("browser.runSteps");
    harness.nextDeviceOutput = Json.obj().put("sessionId", "s_one");

    var run = harness.engine.start(harness.workflow.getId(), 1, Json.obj(), "manual");

    assertThat(run.status()).isEqualTo(RunStatus.DONE);
    assertThat(harness.deviceRequests).extracting(r -> r.action())
        .containsExactly("browser.runSteps", "browser.open", "browser.getText", "browser.close");
  }

  @Test
  void aBatchThatStopsPartWayRunsTheRestOneByOne() {
    EngineHarness harness = new EngineHarness(line(node("open", NodeType.DEVICE_ACTION, OPEN),
        node("read", NodeType.DEVICE_ACTION, READ)));
    harness.deviceActions.setFastReplay(true);
    com.fasterxml.jackson.databind.node.ObjectNode batch = Json.obj().put("failedAt", "read");
    batch.putObject("steps").set("open", Json.obj().put("sessionId", "s_batch"));
    harness.deviceOutputs.put("browser.runSteps", batch);
    harness.deviceOutputs.put("browser.getText", Json.obj().put("text", "Total: 12"));

    var run = harness.engine.start(harness.workflow.getId(), 1, Json.obj(), "manual");

    assertThat(run.status()).isEqualTo(RunStatus.DONE);
    assertThat(harness.deviceRequests).extracting(r -> r.action())
        .containsExactly("browser.runSteps", "browser.getText", "browser.close");
    assertThat(harness.deviceRequests.get(1).input().path("session").asText()).isEqualTo("s_batch");
  }

  @Test
  void fastReplayLeavesModulesWithOtherStepsOrStepReferencesAlone() {
    String reads = """
        {"preset":"web","action":"browser.setText","version":1,"target":"sameBrowser",
         "inputs":{"element":{"strategies":[{"by":"text","value":"Name"}]},
                   "text":"{{ nodes.open.title }}"}}
        """;
    EngineHarness harness = new EngineHarness(line(node("open", NodeType.DEVICE_ACTION, OPEN),
        node("type", NodeType.DEVICE_ACTION, reads)));
    harness.deviceActions.setFastReplay(true);
    harness.nextDeviceOutput = Json.obj().put("sessionId", "s_one").put("title", "Hi");

    harness.engine.start(harness.workflow.getId(), 1, Json.obj(), "manual");

    assertThat(harness.deviceRequests).extracting(r -> r.action())
        .doesNotContain("browser.runSteps");
  }
}
