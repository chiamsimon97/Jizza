package com.jizza.engine.workflow.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jizza.engine.api.device.DeviceCommandStatus;
import com.jizza.engine.api.enums.NodeType;
import com.jizza.engine.api.enums.RunStatus;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.workflow.graph.WorkflowEdge;
import com.jizza.engine.api.workflow.graph.WorkflowGraph;
import com.jizza.engine.api.workflow.graph.WorkflowNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Plan 07: "wait for what happens" outcomes that stop a run, and steps that cannot be undone. */
class RunReliabilityTest {

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

  private static final String WAIT = """
      {"preset":"web","action":"browser.waitFor","version":1,"target":"sameBrowser",
       "inputs":{"outcomes":[{"name":"Results","text":"trains found"},
                             {"name":"Nothing found","text":"No trains"}]},
       "onOutcome":{"Results":{"then":"continue"},
                    "Nothing found":{"then":"stop","message":"No trains on that day"}}}
      """;
  private static final String PAY = """
      {"preset":"web","action":"browser.click","version":2,"target":"sameBrowser",
       "inputs":{"element":{"strategies":[{"by":"text","value":"Pay now"}]}},"irreversible":true}
      """;

  @Test
  void anOutcomeThatCarriesOnLetsTheRunFinish() {
    EngineHarness harness = new EngineHarness(line(node("wait", NodeType.DEVICE_ACTION, WAIT)));
    harness.deviceOutputs.put("browser.waitFor", Json.obj().put("outcome", "Results").put("index", 0));

    var run = harness.engine.start(harness.workflow.getId(), 1, Json.obj(), "manual");

    assertThat(run.status()).isEqualTo(RunStatus.DONE);
    assertThat(run.context().path("nodes").path("wait").path("outcome").asText()).isEqualTo("Results");
  }

  @Test
  void anOutcomeThatStopsAsksThePersonWithTheSitesMessageAndIsNotRetried() {
    EngineHarness harness = new EngineHarness(line(node("wait", NodeType.DEVICE_ACTION, WAIT)));
    harness.deviceOutputs.put("browser.waitFor",
        Json.obj().put("outcome", "Nothing found").put("index", 1));

    var run = harness.engine.start(harness.workflow.getId(), 1, Json.obj(), "manual");

    assertThat(run.status()).isEqualTo(RunStatus.PAUSED_WAITING_INSTRUCTION);
    assertThat(harness.deviceRequests).hasSize(1);
    assertThat(harness.raised.get(0).body()).contains("No trains on that day");
  }

  @Test
  void aStepThatCannotBeUndoneIsNeverSentAgainByItself() {
    EngineHarness harness = new EngineHarness(line(node("pay", NodeType.DEVICE_ACTION, PAY)));
    harness.nextDeviceStatus = DeviceCommandStatus.FAILED;
    harness.nextDeviceOutput = Json.obj().set("error", Json.obj().put("kind", "timed_out"));

    var run = harness.engine.start(harness.workflow.getId(), 1, Json.obj(), "manual");

    assertThat(run.status()).isEqualTo(RunStatus.PAUSED_WAITING_INSTRUCTION);
    // A timeout is normally tried again; not on a Pay step.
    assertThat(harness.deviceRequests).hasSize(1);
    var card = harness.raised.get(0);
    assertThat(card.body()).contains("may have gone through already");
    assertThat(card.context().path("irreversible").asBoolean()).isTrue();
    assertThat(card.context().path("choices").toString())
        .contains("I checked, it did not go through: try again").doesNotContain("\"Try again\"");
    // Never tried again when nobody answers.
    assertThat(card.context().path("defaultChoice").asText()).isEqualTo("Stop the run");

    // A plain Try again, or a written note, could pay twice.
    assertThatThrownBy(() -> harness.engine.resume(run.id(), Json.obj().put("instruction", "Try again")))
        .isInstanceOf(ValidationException.class).hasMessageContaining("may already have gone through");
    assertThatThrownBy(() -> harness.engine.resume(run.id(), Json.obj().put("instruction", "use the other card")))
        .isInstanceOf(ValidationException.class);
    assertThat(harness.deviceRequests).hasSize(1);

    harness.nextDeviceStatus = DeviceCommandStatus.SUCCEEDED;
    harness.nextDeviceOutput = Json.obj();
    var again = harness.engine.resume(run.id(),
        Json.obj().put("instruction", "I checked, it did not go through: try again"));
    assertThat(again.status()).isEqualTo(RunStatus.DONE);
    assertThat(harness.deviceRequests).hasSize(2);
  }

  @Test
  void anOrdinaryStepStillOffersAPlainTryAgain() {
    EngineHarness harness = new EngineHarness(line(node("read", NodeType.DEVICE_ACTION, """
        {"preset":"web","action":"browser.getText","version":1,"target":"sameBrowser",
         "inputs":{"element":{"strategies":[{"by":"text","value":"Total"}]}}}
        """)));
    harness.nextDeviceStatus = DeviceCommandStatus.FAILED;
    harness.nextDeviceOutput = Json.obj().set("error", Json.obj().put("kind", "element_not_found"));

    harness.engine.start(harness.workflow.getId(), 1, Json.obj(), "manual");

    var card = harness.raised.get(0);
    assertThat(card.context().path("choices").toString()).contains("\"Try again\"")
        .doesNotContain("I checked");
  }

  @Test
  void aBrowserThatWasNeverReachedAsksThePersonToOpenItAndCarriesOnFromThere() {
    EngineHarness harness = new EngineHarness(line(node("pay", NodeType.DEVICE_ACTION, PAY)));
    harness.nextDeviceStatus = DeviceCommandStatus.EXPIRED;
    harness.nextDeviceErrorCode = "device_offline";
    harness.neverDelivered = true;

    var run = harness.engine.start(harness.workflow.getId(), 1, Json.obj(), "manual");

    // No retries on a schedule: they would only wait again.
    assertThat(run.status()).isEqualTo(RunStatus.PAUSED_WAITING_INSTRUCTION);
    assertThat(harness.deviceRequests).hasSize(1);
    var card = harness.raised.get(0);
    assertThat(card.body()).contains("was not reachable, so this step was not started")
        .doesNotContain("may have gone through");
    // It was never sent, so a plain Try again is safe even on a Pay step.
    assertThat(card.context().path("choices").toString()).contains("\"Try again\"");

    harness.neverDelivered = false;
    harness.nextDeviceStatus = DeviceCommandStatus.SUCCEEDED;
    harness.nextDeviceErrorCode = null;
    var again = harness.engine.resume(run.id(), Json.obj().put("instruction", "Try again"));
    assertThat(again.status()).isEqualTo(RunStatus.DONE);
  }
}
