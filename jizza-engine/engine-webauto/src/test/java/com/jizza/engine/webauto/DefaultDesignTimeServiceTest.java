package com.jizza.engine.webauto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jizza.engine.api.authz.Access;
import com.jizza.engine.api.autonomy.AutonomyGate;
import com.jizza.engine.api.autonomy.Decision;
import com.jizza.engine.api.device.DeviceActionCatalog;
import com.jizza.engine.api.device.DeviceActionSpec;
import com.jizza.engine.api.device.DeviceCommandDto;
import com.jizza.engine.api.device.DeviceCommandRequest;
import com.jizza.engine.api.device.DeviceCommandStatus;
import com.jizza.engine.api.device.DeviceCommands;
import com.jizza.engine.api.device.DeviceDto;
import com.jizza.engine.api.device.DevicePlatform;
import com.jizza.engine.api.device.DeviceStatus;
import com.jizza.engine.api.device.Devices;
import com.jizza.engine.api.dto.AgentDto;
import com.jizza.engine.api.enums.ActionType;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.orchestrator.AgentDirectory;
import com.jizza.engine.api.util.Json;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DefaultDesignTimeServiceTest {

  @Test
  void judgesAndRunsALiteralInteractiveCommand() {
    Devices devices = mock(Devices.class);
    DeviceCommands commands = mock(DeviceCommands.class);
    DeviceActionCatalog catalog = mock(DeviceActionCatalog.class);
    AgentDirectory agents = mock(AgentDirectory.class);
    AutonomyGate autonomy = mock(AutonomyGate.class);
    Access access = new Access(UUID.randomUUID(), UUID.randomUUID());
    DeviceDto browser = browser(access);
    DeviceActionSpec spec = new DeviceActionSpec("browser.open", 2, "browser", ActionType.READ,
        "Open URL", null, Json.obj().put("type", "object"), Json.obj().put("type", "object"),
        List.of("site"), List.of("browser"), 120);
    UUID commandId = UUID.randomUUID();
    DeviceCommandDto sent = command(commandId, browser, DeviceCommandStatus.QUEUED, null);
    DeviceCommandDto done = command(commandId, browser, DeviceCommandStatus.SUCCEEDED,
        Json.obj().put("sessionId", "s_12345678"));
    when(devices.list(access.workspaceId(), access.userId())).thenReturn(List.of(browser));
    when(catalog.resolve("browser.open", 2)).thenReturn(Optional.of(spec));
    when(agents.orchestrator(access.userId())).thenReturn(new AgentDto(UUID.randomUUID(),
        access.userId(), "jizza", "Jizza", null, "", true, true));
    when(autonomy.decide(any())).thenReturn(Decision.allow("default"));
    when(commands.submit(any())).thenReturn(sent);
    when(commands.await(any(), any())).thenReturn(done);

    var result = new DefaultDesignTimeService(devices, commands, catalog, agents, autonomy)
        .testStep(access, browser.id(), "browser.open", 2,
            Json.obj().put("url", "{{ input.url }}"));

    assertThat(result.status()).isEqualTo("succeeded");
    assertThat(result.output().path("sessionId").asText()).isEqualTo("s_12345678");
    ArgumentCaptor<DeviceCommandRequest> request = ArgumentCaptor.forClass(DeviceCommandRequest.class);
    verify(commands).submit(request.capture());
    assertThat(request.getValue().interactive()).isTrue();
    assertThat(request.getValue().input().path("url").asText()).isEqualTo("{{ input.url }}");
  }

  @Test
  void hidesAnotherAccountsBrowserAsMissing() {
    Devices devices = mock(Devices.class);
    Access access = new Access(UUID.randomUUID(), UUID.randomUUID());
    when(devices.list(access.workspaceId(), access.userId())).thenReturn(List.of());
    DefaultDesignTimeService service = new DefaultDesignTimeService(devices, mock(DeviceCommands.class),
        mock(DeviceActionCatalog.class), mock(AgentDirectory.class), mock(AutonomyGate.class));

    assertThatThrownBy(() -> service.testStep(access, UUID.randomUUID(), "browser.open", 2,
        Json.obj())).isInstanceOf(NotFoundException.class);
  }

  private static DeviceDto browser(Access access) {
    return new DeviceDto(UUID.randomUUID(), access.workspaceId(), access.userId(), "Chrome",
        DevicePlatform.BROWSER, null, null, 1, DeviceStatus.ACTIVE, List.of(), "browser", true,
        "idle", Json.obj(), List.of("browser.open@2"), false, List.of(), Instant.now(), Instant.now());
  }

  private static DeviceCommandDto command(UUID id, DeviceDto browser, DeviceCommandStatus status,
                                          com.fasterxml.jackson.databind.JsonNode output) {
    return new DeviceCommandDto(id, browser.id(), browser.name(), browser.ownerUserId(), null, null,
        "browser.open", 2, Json.obj(), status, output, null, null, null, List.of(),
        Instant.now().plusSeconds(60), Instant.now(), null, null,
        status.finished() ? Instant.now() : null);
  }
}
