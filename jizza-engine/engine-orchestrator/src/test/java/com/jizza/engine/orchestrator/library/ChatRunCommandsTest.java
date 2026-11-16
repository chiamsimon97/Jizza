package com.jizza.engine.orchestrator.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jizza.engine.api.dto.RunDto;
import com.jizza.engine.api.enums.RunStatus;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.workflow.WorkflowEngine;
import com.jizza.engine.persistence.entity.WorkflowEntity;
import com.jizza.engine.persistence.entity.WorkflowRunEntity;
import com.jizza.engine.persistence.repository.WorkflowRepository;
import com.jizza.engine.persistence.repository.WorkflowRunRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class ChatRunCommandsTest {

  private final UUID userId = UUID.randomUUID();
  private final UUID conversationId = UUID.randomUUID();
  private WorkflowRunRepository runs;
  private WorkflowEngine engine;
  private ChatRunCommands commands;
  private WorkflowRunEntity run;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    runs = mock(WorkflowRunRepository.class);
    WorkflowRepository workflows = mock(WorkflowRepository.class);
    engine = mock(WorkflowEngine.class);
    WorkflowEntity w = new WorkflowEntity();
    w.setId(UUID.randomUUID());
    w.setUserId(userId);
    w.setName("Train booking");
    w.setGraph(Json.read("{\"nodes\":[{\"id\":\"n2\",\"label\":\"Choose a train\"}]}"));
    when(workflows.findById(w.getId())).thenReturn(Optional.of(w));
    run = new WorkflowRunEntity();
    run.setId(UUID.randomUUID());
    run.setUserId(userId);
    run.setWorkflowId(w.getId());
    run.setStatus(RunStatus.PAUSED_WAITING_INSTRUCTION);
    run.setCurrentNode("n2");
    run.setCreatedAt(Instant.now());
    when(runs.findTop5ByUserIdAndTriggerSourceEndingWithOrderByCreatedAtDesc(userId, "chat:" + conversationId))
        .thenReturn(List.of(run));
    ObjectProvider<WorkflowEngine> p = mock(ObjectProvider.class);
    when(p.getIfAvailable()).thenReturn(engine);
    commands = new ChatRunCommands(runs, workflows, p);
  }

  @Test
  void stopItEndsTheRunThisChatStarted() {
    var turn = commands.handle(userId, conversationId, "stop it").orElseThrow();
    verify(engine).cancel(run.getId());
    assertThat(turn.text()).startsWith("Stopped **Train booking**");
    assertThat(commands.handle(userId, conversationId, "Please cancel the run.")).isPresent();
  }

  @Test
  void aFinishedRunIsNotStoppedAgain() {
    run.setStatus(RunStatus.DONE);
    assertThat(commands.handle(userId, conversationId, "cancel").orElseThrow().text()).contains("isn't running");
    verify(engine, never()).cancel(any());
  }

  @Test
  void statusSaysWhereItIs() {
    var turn = commands.handle(userId, conversationId, "how's it going?").orElseThrow();
    assertThat(turn.text()).contains("waiting for you").contains("Choose a train");
    assertThat(turn.actions().get(0).payload().path("followUp").asBoolean()).isTrue();
  }

  @Test
  void aQuestionAboutAnOutputIsAnsweredFromTheFinishedRun() {
    run.setStatus(RunStatus.DONE);
    when(engine.get(run.getId())).thenReturn(new RunDto(run.getId(), userId, run.getWorkflowId(), 1, "x",
        RunStatus.DONE, null, Json.read("{\"result\":{\"pnrNumber\":\"4521337890\",\"fare\":\"1200\"}}"),
        Instant.now(), Instant.now(), List.of()));
    var turn = commands.handle(userId, conversationId, "what's the PNR?").orElseThrow();
    assertThat(turn.text()).contains("**pnrNumber**: 4521337890").doesNotContain("fare");
    assertThat(commands.handle(userId, conversationId, "what's the weather?")).isEmpty();
  }

  @Test
  void anythingElseOrAChatWithoutARunGoesToJizza() {
    assertThat(commands.handle(userId, conversationId, "stop using Chrome for this workflow next time")).isEmpty();
    assertThat(commands.handle(userId, conversationId, "book another one for tomorrow")).isEmpty();
    assertThat(commands.handle(userId, UUID.randomUUID(), "stop it")).isEmpty();
    assertThat(commands.handle(userId, null, "stop it")).isEmpty();
    run.setCreatedAt(Instant.now().minus(ChatRunCommands.RECENT).minusSeconds(5));
    assertThat(commands.handle(userId, conversationId, "stop it")).isEmpty();
    verify(engine, never()).cancel(eq(run.getId()));
  }
}
