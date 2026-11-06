package com.jizza.platform.usecase.device;

import static org.assertj.core.api.Assertions.assertThat;

import com.jizza.platform.usecase.device.PhoneAskUseCase.CommandType;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PhoneAskUseCaseTest {

  private final UUID flow = UUID.randomUUID();
  private final UUID run = UUID.randomUUID();
  private final Map<UUID, String> runnable = Map.of(flow, "Monthly expenses");
  private final Map<UUID, String> active = Map.of(run, "Invoice sync");

  @Test
  void readsAnAnswerWithAnAllowedCommand() {
    var answer = PhoneAskUseCase.read("""
        ```json
        {"answer": "Starting it once you confirm.", "command": {"type": "RUN_WORKFLOW", "workflowId": "%s"}}
        ```""".formatted(flow), runnable, active);
    assertThat(answer.answer()).isEqualTo("Starting it once you confirm.");
    assertThat(answer.command().type()).isEqualTo(CommandType.RUN_WORKFLOW);
    assertThat(answer.command().label()).isEqualTo("Run Monthly expenses");
  }

  @Test
  void dropsACommandTheFactsDoNotAllow() {
    var unknownFlow = PhoneAskUseCase.read("""
        {"answer": "Sure.", "command": {"type": "RUN_WORKFLOW", "workflowId": "%s"}}
        """.formatted(UUID.randomUUID()), runnable, active);
    assertThat(unknownFlow.command()).isNull();

    var finishedRun = PhoneAskUseCase.read("""
        {"answer": "Sure.", "command": {"type": "CANCEL_RUN", "runId": "%s"}}
        """.formatted(UUID.randomUUID()), runnable, active);
    assertThat(finishedRun.command()).isNull();

    var madeUp = PhoneAskUseCase.read("""
        {"answer": "Sure.", "command": {"type": "DELETE_EVERYTHING"}}
        """, runnable, active);
    assertThat(madeUp.command()).isNull();

    var cancel = PhoneAskUseCase.read("""
        {"answer": "Confirm to cancel.", "command": {"type": "CANCEL_RUN", "runId": "%s"}}
        """.formatted(run), runnable, active);
    assertThat(cancel.command().label()).isEqualTo("Cancel the Invoice sync run");
  }

  @Test
  void plainTextIsKeptAsTheAnswer() {
    var answer = PhoneAskUseCase.read("Two runs failed today.", runnable, active);
    assertThat(answer.answer()).isEqualTo("Two runs failed today.");
    assertThat(answer.command()).isNull();
  }
}
