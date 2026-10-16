package com.jizza.platform.usecase.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jizza.engine.api.agent.CapabilityRegistry;
import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.dto.AgentDto;
import com.jizza.engine.api.dto.ConnectionDto;
import com.jizza.engine.api.enums.AuthScheme;
import com.jizza.engine.api.enums.CapabilityType;
import com.jizza.engine.api.enums.ConnectionStatus;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.orchestrator.AgentDirectory;
import com.jizza.engine.api.service.ServiceDefinitions;
import com.jizza.engine.api.service.ServiceModel.Action;
import com.jizza.engine.api.service.ServiceModel.ApiInput;
import com.jizza.engine.api.service.ServiceModel.Info;
import com.jizza.engine.api.service.ServiceRunner;
import com.jizza.engine.api.connector.ConnectionStore;
import com.jizza.platform.usecase.connection.ConnectionUseCase;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The rules for building, publishing and handing out a connector service. */
class ConnectorServiceUseCaseTest {

  private final UUID userId = UUID.randomUUID();
  private final com.jizza.engine.api.authz.Access access =
      new com.jizza.engine.api.authz.Access(userId, userId);
  private final UUID serviceId = UUID.randomUUID();
  private ServiceDefinitions definitions;
  private CapabilityRegistry capabilities;
  private AgentDirectory agents;
  private ConnectorServiceUseCase useCase;

  private Info info(String baseUrl, AuthScheme scheme, boolean credential) {
    return new Info(serviceId, "Inventory", "inventory", null, null, baseUrl, scheme,
        ConnectionStatus.NEW, credential, "draft", 0, null, 0, 0, 0, null, null);
  }

  @BeforeEach
  void setUp() {
    definitions = mock(ServiceDefinitions.class);
    capabilities = mock(CapabilityRegistry.class);
    agents = mock(AgentDirectory.class);
    ConnectionStore store = mock(ConnectionStore.class);
    ConnectionDto connection = mock(ConnectionDto.class);
    when(connection.userId()).thenReturn(userId);
    when(connection.workspaceId()).thenReturn(userId);
    when(store.find(serviceId)).thenReturn(Optional.of(connection));
    useCase = new ConnectorServiceUseCase(definitions, mock(ServiceRunner.class),
        new ConnectionUseCase(store, mock(AuditLog.class)), capabilities, agents,
        mock(AuditLog.class), "http://localhost/api/oauth/callback");
  }

  @Test
  void publishingListsEverythingStillMissing() {
    when(definitions.find(serviceId)).thenReturn(Optional.of(info(null, AuthScheme.API_KEY, false)));
    assertThatThrownBy(() -> useCase.publish(access, serviceId))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("base URL")
        .hasMessageContaining("credential")
        .hasMessageContaining("action or trigger");
    verify(definitions, never()).setVersionStatus(any(), any());
  }

  @Test
  void aCompleteServicePublishes() {
    when(definitions.find(serviceId))
        .thenReturn(Optional.of(info("https://api.test", AuthScheme.NO_AUTH, false)));
    when(definitions.actions(serviceId)).thenReturn(List.of(action(List.of())));
    when(definitions.setVersionStatus(serviceId, "live"))
        .thenReturn(info("https://api.test", AuthScheme.NO_AUTH, false));
    useCase.publish(access, serviceId);
    verify(definitions).setVersionStatus(serviceId, "live");
  }

  @Test
  void assigningAgentsGrantsAndRevokesTheDifference() {
    UUID keep = agent();
    UUID add = agent();
    UUID drop = agent();
    Action action = action(List.of(keep, drop));
    when(definitions.find(serviceId))
        .thenReturn(Optional.of(info("https://api.test", AuthScheme.NO_AUTH, false)));
    when(definitions.action(action.id())).thenReturn(Optional.of(action));

    useCase.assignAgents(access, serviceId, action.id(), List.of(keep, add));

    verify(capabilities).grant(add, CapabilityType.SERVICE_ACTION, action.id());
    verify(capabilities).revoke(drop, CapabilityType.SERVICE_ACTION, action.id());
    verify(capabilities, never()).grant(eq(keep), any(), any());
  }

  @Test
  void anotherUsersAgentCannotBeAssigned() {
    UUID stranger = UUID.randomUUID();
    when(agents.byId(stranger)).thenReturn(Optional.of(new AgentDto(stranger, UUID.randomUUID(),
        "x", "X", null, "p", false, true)));
    Action action = action(List.of());
    when(definitions.find(serviceId))
        .thenReturn(Optional.of(info("https://api.test", AuthScheme.NO_AUTH, false)));
    when(definitions.action(action.id())).thenReturn(Optional.of(action));
    assertThatThrownBy(() -> useCase.assignAgents(access, serviceId, action.id(), List.of(stranger)))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void apisAreValidatedBeforeTheyAreStored() {
    when(definitions.find(serviceId))
        .thenReturn(Optional.of(info("https://api.test", AuthScheme.NO_AUTH, false)));
    assertThatThrownBy(() -> useCase.saveApi(access, serviceId, null, new ApiInput("Bad Name",
        "List", null, null, "GET", "/x", null, null, null)))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> useCase.saveApi(access, serviceId, null, new ApiInput("list",
        "List", null, null, "GET", "https://evil.test/x", null, null, null)))
        .isInstanceOf(ValidationException.class).hasMessageContaining("base URL");
    assertThatThrownBy(() -> useCase.saveApi(access, serviceId, null, new ApiInput("list",
        "List", null, null, "TRACE", "/x", null, null, null)))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void someoneElsesServiceIsNotFound() {
    UUID other = UUID.randomUUID();
    assertThatThrownBy(() -> useCase.detail(new com.jizza.engine.api.authz.Access(other, other),
        serviceId))
        .isInstanceOf(NotFoundException.class);
  }

  private UUID agent() {
    UUID id = UUID.randomUUID();
    when(agents.byId(id)).thenReturn(Optional.of(new AgentDto(id, userId, "k", "A", null, "p",
        false, true)));
    return id;
  }

  private Action action(List<UUID> agentIds) {
    return new Action(UUID.randomUUID(), serviceId, "create_invoice", "Create invoice", null,
        UUID.randomUUID(), null, true, true, "inventory__create_invoice", agentIds);
  }
}
