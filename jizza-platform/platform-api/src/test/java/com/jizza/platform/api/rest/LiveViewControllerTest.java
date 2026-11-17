package com.jizza.platform.api.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jizza.engine.api.device.DeviceCommandDto;
import com.jizza.engine.api.device.DeviceCommandRequest;
import com.jizza.engine.api.device.DeviceCommandStatus;
import com.jizza.engine.api.device.DeviceCommands;
import com.jizza.engine.api.dto.RunDto;
import com.jizza.engine.api.enums.RunStatus;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.workflow.WorkflowEngine;
import com.jizza.engine.security.session.JizzaPrincipal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class LiveViewControllerTest {

  private final UUID me = UUID.randomUUID();
  private final UUID device = UUID.randomUUID();
  private final WorkflowEngine engine = mock(WorkflowEngine.class);
  private final DeviceCommands broker = mock(DeviceCommands.class);
  private LiveViewController controller;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
        new JizzaPrincipal(me, UUID.randomUUID(), Instant.now(), true), null, List.of()));
    ObjectProvider<DeviceCommands> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(broker);
    controller = new LiveViewController(engine, provider);
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  private RunDto run(UUID owner, boolean withBrowser) {
    var context = Json.obj();
    if (withBrowser) {
      context.putObject("browser").put("deviceId", device.toString()).put("sessionId", "s_abc");
    }
    return new RunDto(UUID.randomUUID(), owner, UUID.randomUUID(), 1, "manual",
        RunStatus.RUNNING, "step", context, Instant.now(), Instant.now(), List.of());
  }

  private static DeviceCommandDto command(DeviceCommandRequest r, UUID id, DeviceCommandStatus status) {
    return new DeviceCommandDto(id, r.target().deviceId(), "Chrome", r.userId(), r.runId(),
        r.nodeId(), r.action(), 1, r.input(), status,
        status == DeviceCommandStatus.SUCCEEDED ? Json.obj().put("file", "pic-1") : null,
        null, null, null, List.of(), Instant.now().plusSeconds(30), Instant.now(), Instant.now(),
        Instant.now(), Instant.now());
  }

  @Test
  void takesAPictureOfTheRunsOwnTabWithItsOwnBrowser() {
    RunDto run = run(me, true);
    when(engine.get(run.id())).thenReturn(run);
    UUID id = UUID.randomUUID();
    AtomicReference<DeviceCommandRequest> sent = new AtomicReference<>();
    when(broker.submit(any())).thenAnswer(inv -> {
      sent.set(inv.getArgument(0));
      return command(sent.get(), id, DeviceCommandStatus.QUEUED);
    });
    when(broker.await(any(), any())).thenAnswer(inv ->
        command(sent.get(), id, DeviceCommandStatus.SUCCEEDED));

    var picture = controller.take(run.id());

    assertThat(picture.status()).isEqualTo("ready");
    assertThat(picture.file()).isEqualTo("pic-1");
    assertThat(picture.device()).isEqualTo(device.toString());
    assertThat(sent.get().action()).isEqualTo("browser.screenshot");
    assertThat(sent.get().input().path("session").asText()).isEqualTo("s_abc");
    assertThat(sent.get().nodeId()).isEqualTo("live-view");
    assertThat(sent.get().target().deviceId()).isEqualTo(device);
  }

  @Test
  void refusesAnotherPersonsRunAndARunWithNoBrowser() {
    RunDto theirs = run(UUID.randomUUID(), true);
    when(engine.get(theirs.id())).thenReturn(theirs);
    assertThatThrownBy(() -> controller.take(theirs.id())).isInstanceOf(NotFoundException.class);

    RunDto noBrowser = run(me, false);
    when(engine.get(noBrowser.id())).thenReturn(noBrowser);
    assertThatThrownBy(() -> controller.take(noBrowser.id()))
        .isInstanceOf(ValidationException.class).hasMessageContaining("no browser");
  }

  @Test
  void onlyShowsPicturesTakenForThisRunsLiveView() {
    RunDto run = run(me, true);
    when(engine.get(run.id())).thenReturn(run);
    UUID other = UUID.randomUUID();
    var request = new DeviceCommandRequest(me, null, com.jizza.engine.api.device.DeviceTarget.device(device),
        "browser.screenshot", 1, Json.obj(), null, run.id(), "step_3", "k", false);
    when(broker.find(other)).thenReturn(Optional.of(command(request, other, DeviceCommandStatus.SUCCEEDED)));
    assertThatThrownBy(() -> controller.get(run.id(), other)).isInstanceOf(NotFoundException.class);
  }
}
