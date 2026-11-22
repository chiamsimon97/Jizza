package com.jizza.platform.usecase.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.authz.Access;
import com.jizza.engine.api.device.DeviceDto;
import com.jizza.engine.api.device.DevicePlatform;
import com.jizza.engine.api.device.DeviceStatus;
import com.jizza.engine.api.device.Devices;
import com.jizza.engine.api.dto.AgentDto;
import com.jizza.engine.api.enums.ActionType;
import com.jizza.engine.api.enums.AuthScheme;
import com.jizza.engine.api.enums.ConnectionStatus;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.mcp.McpAuthType;
import com.jizza.engine.api.mcp.McpServerDto;
import com.jizza.engine.api.mcp.McpServers;
import com.jizza.engine.api.mcp.McpToolDto;
import com.jizza.engine.api.orchestrator.AgentDirectory;
import com.jizza.engine.api.service.ServiceDefinitions;
import com.jizza.engine.api.service.ServiceModel.Action;
import com.jizza.engine.api.service.ServiceModel.Info;
import com.jizza.platform.usecase.device.DeviceUseCase;
import com.jizza.platform.usecase.mcp.McpServerUseCase;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** One kind of card for services, MCP servers and devices, and one way to hand each to agents. */
class ConnectorHubUseCaseTest {

  private final UUID userId = UUID.randomUUID();
  private final Access access = new Access(userId, userId);
  private final UUID jizza = UUID.randomUUID();
  private final UUID kai = UUID.randomUUID();
  private final UUID cato = UUID.randomUUID();

  private ServiceDefinitions services;
  private ConnectorServiceUseCase serviceUseCase;
  private McpServerUseCase mcpUseCase;
  private Devices devices;
  private DeviceUseCase deviceUseCase;
  private ConnectorHubUseCase hub;

  @BeforeEach
  void setUp() {
    services = mock(ServiceDefinitions.class);
    serviceUseCase = mock(ConnectorServiceUseCase.class);
    mcpUseCase = mock(McpServerUseCase.class);
    devices = mock(Devices.class);
    deviceUseCase = mock(DeviceUseCase.class);
    AgentDirectory agents = mock(AgentDirectory.class);
    when(agents.list(userId)).thenReturn(List.of(
        new AgentDto(jizza, userId, "jizza", "Jizza", null, "p", true, true),
        new AgentDto(kai, userId, "finance", "Kai", null, "p", false, true),
        new AgentDto(cato, userId, "professional", "Cato", null, "p", false, true)));
    hub = new ConnectorHubUseCase(services, serviceUseCase, mock(McpServers.class), mcpUseCase,
        devices, deviceUseCase, agents, mock(AuditLog.class));
  }

  @Test
  void everyKindAnswersTheSameQuestions() {
    Info zoho = service(ConnectionStatus.NEEDS_REAUTH);
    when(serviceUseCase.list(access)).thenReturn(List.of(zoho));
    when(services.actions(zoho.id())).thenReturn(List.of(
        action(false, List.of(jizza, kai)), action(true, List.of(jizza))));
    McpServerDto kite = mcp("NONE", "login", null);
    when(mcpUseCase.list(access)).thenReturn(List.of(kite));
    DeviceDto laptop = device(true);
    when(deviceUseCase.list(access)).thenReturn(List.of(laptop));
    when(devices.agentKeys(laptop.id())).thenReturn(List.of("jizza"));

    List<ConnectorHubUseCase.Card> cards = hub.cards(access);

    var service = cards.get(0);
    assertThat(service.status()).isEqualTo("needs_sign_in");
    assertThat(service.toolCount()).isEqualTo(2);
    assertThat(service.types()).isEqualTo(new ConnectorHubUseCase.Types(1, 1, 0, 0));
    assertThat(service.agentKeys()).containsExactlyInAnyOrder("jizza", "finance");
    // Kai has the read but not the change.
    assertThat(service.partial()).isTrue();

    var server = cards.get(1);
    // It signs in inside its own session, and none is open since the last restart.
    assertThat(server.status()).isEqualTo("needs_sign_in");
    assertThat(server.types()).isEqualTo(new ConnectorHubUseCase.Types(1, 0, 1, 1));
    assertThat(server.path()).isEqualTo("/connectors/mcp/" + kite.id());

    var device = cards.get(2);
    assertThat(device.status()).isEqualTo("ready");
    assertThat(device.agentKeys()).containsExactly("jizza");
  }

  @Test
  void anMcpServerSignedInThisSessionIsReady() {
    when(mcpUseCase.list(access)).thenReturn(List.of(mcp("NONE", "login", Instant.now())));
    assertThat(hub.cards(access).get(0).status()).isEqualTo("ready");
  }

  @Test
  void givingAServiceToAnAgentGrantsEveryActionAndTakingItAwayRevokesEvery() {
    Info zoho = service(ConnectionStatus.CONNECTED);
    Action read = action(false, List.of(jizza, kai));
    Action write = action(true, List.of(jizza));
    when(services.actions(zoho.id())).thenReturn(List.of(read, write));
    when(serviceUseCase.detail(access, zoho.id())).thenReturn(
        new ConnectorServiceUseCase.ServiceDetail(zoho, List.of(), List.of(), List.of(), List.of(),
            List.of(), null));

    // Cato is new and Kai is dropped; Jizza is untouched.
    hub.setAgents(access, "service", zoho.id(), List.of("jizza", "professional"));

    verify(serviceUseCase).assignAgents(access, zoho.id(), read.id(), List.of(jizza, cato));
    verify(serviceUseCase).assignAgents(access, zoho.id(), write.id(), List.of(jizza, cato));
  }

  @Test
  void anAgentLeftAloneKeepsItsOwnActions() {
    Info zoho = service(ConnectionStatus.CONNECTED);
    Action read = action(false, List.of(kai));
    Action write = action(true, List.of());
    when(services.actions(zoho.id())).thenReturn(List.of(read, write));
    when(serviceUseCase.detail(access, zoho.id())).thenReturn(
        new ConnectorServiceUseCase.ServiceDetail(zoho, List.of(), List.of(), List.of(), List.of(),
            List.of(), null));

    hub.setAgents(access, "service", zoho.id(), List.of("finance"));

    verify(serviceUseCase, never()).assignAgents(any(), any(), any(), any());
  }

  @Test
  void onlyThePersonsOwnAgentsCanBeNamed() {
    UUID id = UUID.randomUUID();
    assertThatThrownBy(() -> hub.setAgents(access, "device", id, List.of("stranger")))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> hub.setAgents(access, "nothing", id, List.of("jizza")))
        .isInstanceOf(ValidationException.class);
    verify(devices, never()).setAgentKeys(eq(id), any());
  }

  @Test
  void everyAgentIsJustTheStar() {
    DeviceDto laptop = device(false);
    when(deviceUseCase.get(access, laptop.id())).thenReturn(laptop);
    when(deviceUseCase.commands(eq(access), eq(laptop.id()), anyInt())).thenReturn(List.of());
    hub.setAgents(access, "device", laptop.id(), List.of("finance", "*"));
    verify(devices).setAgentKeys(laptop.id(), List.of("*"));
  }

  private Info service(ConnectionStatus status) {
    return new Info(UUID.randomUUID(), "Zoho", "zoho", "Inventory", "CRM", "https://api.test",
        AuthScheme.OAUTH2, status, true, "live", 1, null, 1, 2, 0, null, null);
  }

  private Action action(boolean changes, List<UUID> agentIds) {
    return new Action(UUID.randomUUID(), UUID.randomUUID(), "a" + agentIds.size(), "A", null, null,
        null, true, changes, "svc_a", agentIds);
  }

  private McpServerDto mcp(String auth, String signInTool, Instant signedInAt) {
    List<McpToolDto> tools = List.of(
        new McpToolDto("login", "mcp_kite__login", null, null, null, true, null, false, null, null,
            null),
        new McpToolDto("get_holdings", "mcp_kite__get_holdings", null, null, null, true,
            ActionType.READ, true, null, null, null),
        new McpToolDto("place_order", "mcp_kite__place_order", null, null, null, true,
            ActionType.MONEY, true, null, null, null),
        new McpToolDto("cancel", "mcp_kite__cancel", null, null, null, false, ActionType.DELETE,
            false, null, null, null));
    return new McpServerDto(UUID.randomUUID(), userId, userId, "Kite", "kite", "https://kite.test",
        McpAuthType.valueOf(auth), null, false, false, true, List.of("jizza"), false, "connected",
        null, null, null, tools, null, null, signInTool, signedInAt);
  }

  private DeviceDto device(boolean online) {
    return new DeviceDto(UUID.randomUUID(), userId, userId, "Laptop", DevicePlatform.WINDOWS, null,
        null, 1, DeviceStatus.ACTIVE, List.of(), "tpm", online, "idle", null, List.of(), false,
        List.of(), Instant.now(), Instant.now());
  }
}
