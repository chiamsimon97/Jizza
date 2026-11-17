package com.jizza.engine.workflow.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.jizza.engine.api.enums.NodeType;
import com.jizza.engine.api.enums.RunStatus;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.workflow.graph.WorkflowEdge;
import com.jizza.engine.api.workflow.graph.WorkflowGraph;
import com.jizza.engine.api.workflow.graph.WorkflowNode;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Plan 08: values read as numbers, and the run's one-line result. */
class ResultsAndFilesTest {

  @Test
  void aFareReadAsMoneyAndTheResultMessage() {
    WorkflowGraph graph = new WorkflowGraph(List.of(
        new WorkflowNode("start", NodeType.TRIGGER, "Start", null, null),
        new WorkflowNode("fare", NodeType.DEVICE_ACTION, "Fare", Json.read("""
            {"preset":"web","action":"browser.getText","version":1,"target":"sameBrowser",
             "inputs":{"element":{"strategies":[{"by":"css","value":"#fare"}]}},
             "readAs":{"as":"money"}}"""), null),
        new WorkflowNode("end", NodeType.END, "End", Json.read("""
            {"outputs":[{"name":"fare","value":"{{ nodes.fare.value }}"}],
             "resultMessage":"Booked. Fare {{ result.fare }}"}"""), null)),
        List.of(new WorkflowEdge("start", "fare", null), new WorkflowEdge("fare", "end", null)));
    EngineHarness harness = new EngineHarness(graph);
    harness.deviceOutputs.put("browser.getText", Json.obj().put("text", "₹2,345.00"));

    var run = harness.engine.start(harness.workflow.getId(), 1, Json.obj(), "manual");

    assertThat(run.status()).isEqualTo(RunStatus.DONE);
    var fare = run.context().path("nodes").path("fare");
    assertThat(fare.path("value").decimalValue()).isEqualByComparingTo("2345.00");
    var result = run.context().path("result");
    assertThat(result.path("fare").decimalValue()).isEqualByComparingTo("2345.00");
    assertThat(result.path("resultMessage").asText()).isEqualTo("Booked. Fare 2345");
  }

  @Test
  @SuppressWarnings("unchecked")
  void aChatFileIsHandedToTheBrowserThatUploadsIt() {
    WorkflowGraph graph = new WorkflowGraph(List.of(
        new WorkflowNode("start", NodeType.TRIGGER, "Start", null, null),
        new WorkflowNode("open", NodeType.DEVICE_ACTION, "Open", Json.read("""
            {"preset":"web","action":"browser.open","version":2,"target":"sameBrowser",
             "inputs":{"url":"https://forms.example.test"}}"""), null),
        new WorkflowNode("photo", NodeType.DEVICE_ACTION, "Upload photo", Json.read("""
            {"preset":"web","action":"browser.uploadFile","version":1,"target":"sameBrowser",
             "inputs":{"element":{"strategies":[{"by":"css","value":"#photo"}]},
                       "file":"{{ input.photo }}","transform":{"maxKb":50}}}"""), null),
        new WorkflowNode("end", NodeType.END, "End", null, null)),
        List.of(new WorkflowEdge("start", "open", null), new WorkflowEdge("open", "photo", null),
            new WorkflowEdge("photo", "end", null)));
    EngineHarness harness = new EngineHarness(graph);
    harness.deviceOutputs.put("browser.open", Json.obj().put("sessionId", "s1"));
    java.util.UUID attachment = java.util.UUID.randomUUID();
    java.util.UUID handed = java.util.UUID.randomUUID();
    var devices = org.mockito.Mockito.mock(com.jizza.engine.api.device.Devices.class);
    org.mockito.Mockito.when(devices.file(attachment)).thenReturn(java.util.Optional.empty());
    org.mockito.Mockito.when(devices.handFile(org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.eq("me.jpg"), org.mockito.ArgumentMatchers.eq("image/jpeg"),
        org.mockito.ArgumentMatchers.any())).thenReturn(handed);
    var chat = org.mockito.Mockito.mock(com.jizza.engine.api.chat.ChatFiles.class);
    org.mockito.Mockito.when(chat.content(harness.userId, attachment)).thenReturn(java.util.Optional.of(
        new com.jizza.engine.api.chat.ChatFiles.Content("me.jpg", "image/jpeg", new byte[] {1, 2, 3})));
    var devicesProvider = org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
    org.mockito.Mockito.when(devicesProvider.getIfAvailable()).thenReturn(devices);
    var chatProvider = org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
    org.mockito.Mockito.when(chatProvider.getIfAvailable()).thenReturn(chat);
    harness.deviceActions.setFileSources(devicesProvider, chatProvider);

    var run = harness.engine.start(harness.workflow.getId(), 1,
        Json.obj().put("photo", attachment.toString()), "manual");

    assertThat(run.status()).isEqualTo(RunStatus.DONE);
    var upload = harness.deviceRequests.get(1);
    assertThat(upload.action()).isEqualTo("browser.uploadFile");
    assertThat(upload.input().path("file").path("id").asText()).isEqualTo(handed.toString());
    assertThat(upload.input().path("file").path("name").asText()).isEqualTo("me.jpg");
    assertThat(upload.input().path("file").path("size").asInt()).isEqualTo(3);
    assertThat(upload.input().path("transform").path("maxKb").asInt()).isEqualTo(50);
  }

  @Test
  void aFileThatIsNotTheirsIsRefused() {
    WorkflowGraph graph = new WorkflowGraph(List.of(
        new WorkflowNode("start", NodeType.TRIGGER, "Start", null, null),
        new WorkflowNode("photo", NodeType.DEVICE_ACTION, "Upload photo", Json.read("""
            {"preset":"web","action":"browser.uploadFile","version":1,"target":"any",
             "inputs":{"element":{"strategies":[{"by":"css","value":"#photo"}]},"file":"not-an-id"}}"""), null)),
        List.of(new WorkflowEdge("start", "photo", null)));
    EngineHarness harness = new EngineHarness(graph);
    var run = harness.engine.start(harness.workflow.getId(), 1, Json.obj(), "manual");
    assertThat(run.status()).isEqualTo(RunStatus.PAUSED_WAITING_INSTRUCTION);
    assertThat(harness.deviceRequests).isEmpty();
    assertThat(harness.raised.get(0).body()).contains("Say which file to upload");
  }
}
