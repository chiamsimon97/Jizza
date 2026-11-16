package com.jizza.engine.workflow.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.jizza.engine.api.enums.NodeType;
import com.jizza.engine.api.enums.RunStatus;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.workflow.graph.WorkflowEdge;
import com.jizza.engine.api.workflow.graph.WorkflowGraph;
import com.jizza.engine.api.workflow.graph.WorkflowNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Plan 09: Wait until an exact moment, and looking again until something shows. */
class TimedRunsTest {

  private static WorkflowNode node(String id, NodeType type, String config) {
    return new WorkflowNode(id, type, id, config == null ? null : Json.read(config),
        new WorkflowNode.RetryPolicy(3, 1, 1, 10));
  }

  private static WorkflowGraph line(WorkflowNode... middle) {
    List<WorkflowNode> nodes = new ArrayList<>();
    nodes.add(node("start", NodeType.TRIGGER, null));
    nodes.addAll(List.of(middle));
    nodes.add(node("finish", NodeType.END, null));
    List<WorkflowEdge> edges = new ArrayList<>();
    for (int i = 0; i < nodes.size() - 1; i++) {
      edges.add(new WorkflowEdge(nodes.get(i).id(), nodes.get(i + 1).id(), null));
    }
    return new WorkflowGraph(nodes, edges);
  }

  @Test
  void waitUntilSleepsOnTheServerThenHandsTheBrowserTheExactMoment() {
    // The harness clock reads 2026-10-21T08:00:00Z; 13:30 in Kolkata is 08:00 UTC, so 14:00 is 08:30.
    EngineHarness harness = new EngineHarness(line(node("at", NodeType.DEVICE_ACTION, """
        {"preset":"web","action":"browser.waitUntil","version":1,"target":"sameBrowser",
         "inputs":{"at":"2026-10-21T14:00","timeZone":"Asia/Kolkata"}}
        """)));

    var run = harness.engine.start(harness.workflow.getId(), 1, Json.obj(), "manual");
    assertThat(run.status()).isNotEqualTo(RunStatus.DONE);
    assertThat(harness.deviceRequests).isEmpty();

    harness.clock.advance(Duration.ofMinutes(29));
    var done = harness.engine.wake(run.id());

    assertThat(done.status()).isEqualTo(RunStatus.DONE);
    assertThat(harness.deviceRequests).hasSize(1);
    var sent = harness.deviceRequests.get(0);
    assertThat(sent.input().path("at").asText()).isEqualTo("2026-10-21T08:30:00Z");
    assertThat(sent.input().has("timeZone")).isFalse();
    assertThat(sent.timeout()).isEqualTo(Duration.ofSeconds(120));
  }

  @Test
  void aMomentAlreadyGoneStopsTheRunWithWhy() {
    EngineHarness harness = new EngineHarness(line(node("at", NodeType.DEVICE_ACTION, """
        {"preset":"web","action":"browser.waitUntil","version":1,"target":"sameBrowser",
         "inputs":{"at":"2026-10-21T07:00:00Z"}}
        """)));

    harness.engine.start(harness.workflow.getId(), 1, Json.obj(), "manual");

    assertThat(harness.deviceRequests).isEmpty();
    assertThat(harness.raised.get(0).body()).contains("07:00:00 UTC, 21 Oct 2026 has already passed");
  }

  @Test
  void pollingLooksAgainOnAFreshPageUntilTheSlotShows() {
    EngineHarness harness = new EngineHarness(line(node("slot", NodeType.DEVICE_ACTION, """
        {"preset":"web","action":"browser.waitFor","version":1,"target":"sameBrowser",
         "inputs":{"outcomes":[{"name":"Open","text":"Slot open"}],"timeoutMs":5000},
         "poll":{"everySec":60,"forMinutes":30}}
        """)));
    harness.deviceFailures.put("browser.waitFor",
        Json.obj().set("error", Json.obj().put("kind", "timed_out")));

    var run = harness.engine.start(harness.workflow.getId(), 1, Json.obj(), "manual");
    assertThat(run.status()).isNotEqualTo(RunStatus.DONE);
    assertThat(harness.deviceRequests).hasSize(1);
    assertThat(harness.deviceRequests.get(0).input().has("reloadFirst")).isFalse();
    assertThat(harness.raised).isEmpty();

    harness.deviceOutputs.put("browser.waitFor", Json.obj().put("outcome", "Open").put("index", 0));
    harness.clock.advance(Duration.ofSeconds(61));
    var done = harness.engine.wake(run.id());

    assertThat(done.status()).isEqualTo(RunStatus.DONE);
    assertThat(harness.deviceRequests).hasSize(2);
    assertThat(harness.deviceRequests.get(1).input().path("reloadFirst").asBoolean()).isTrue();
  }

  @Test
  void pollingGivesUpWhenItsTimeRunsOut() {
    EngineHarness harness = new EngineHarness(line(node("slot", NodeType.DEVICE_ACTION, """
        {"preset":"web","action":"browser.waitFor","version":1,"target":"sameBrowser",
         "inputs":{"outcomes":[{"name":"Open","text":"Slot open"}]},
         "poll":{"everySec":60,"maxTries":2}}
        """)));
    harness.deviceFailures.put("browser.waitFor",
        Json.obj().set("error", Json.obj().put("kind", "timed_out")));

    var run = harness.engine.start(harness.workflow.getId(), 1, Json.obj(), "manual");
    harness.deviceFailures.put("browser.waitFor",
        Json.obj().set("error", Json.obj().put("kind", "timed_out")));
    harness.clock.advance(Duration.ofSeconds(61));
    var stopped = harness.engine.wake(run.id());

    assertThat(stopped.status()).isEqualTo(RunStatus.PAUSED_WAITING_INSTRUCTION);
    assertThat(harness.deviceRequests).hasSize(2);
    assertThat(harness.raised.get(0).body()).contains("did not show after 2 looks");
  }
}
