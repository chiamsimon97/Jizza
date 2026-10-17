package com.jizza.engine.connectors.tools.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jizza.engine.api.enums.CapabilityType;
import com.jizza.engine.api.service.ServiceRunner;
import com.jizza.engine.api.tool.ToolCall;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.persistence.entity.AgentCapabilityEntity;
import com.jizza.engine.persistence.entity.ConnectionEntity;
import com.jizza.engine.persistence.entity.GeneratedToolEntity;
import com.jizza.engine.persistence.entity.ServiceActionEntity;
import com.jizza.engine.persistence.repository.AgentCapabilityRepository;
import com.jizza.engine.persistence.repository.AgentRepository;
import com.jizza.engine.persistence.repository.ConnectionRepository;
import com.jizza.engine.persistence.repository.GeneratedToolRepository;
import com.jizza.engine.persistence.repository.ServiceActionRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** An agent sees a service action only once it is granted, enabled and the service is live. */
class ServiceActionToolProviderTest {

  private final UUID agentId = UUID.randomUUID();
  private ConnectionEntity service;
  private ServiceActionEntity action;
  private ServiceRunner runner;
  private ServiceActionToolProvider provider;

  @BeforeEach
  void setUp() {
    service = new ConnectionEntity();
    service.setId(UUID.randomUUID());
    service.setServiceName("Inventory");
    service.setSlug("inventory");
    service.setVersionStatus("live");

    GeneratedToolEntity api = new GeneratedToolEntity();
    api.setId(UUID.randomUUID());
    api.setConnectionId(service.getId());
    api.setInputSchema(Json.obj().put("type", "object"));
    api.setEnabled(true);
    api.setSideEffecting(false);

    action = new ServiceActionEntity();
    action.setId(UUID.randomUUID());
    action.setConnectionId(service.getId());
    action.setName("create_invoice");
    action.setLabel("Create invoice");
    action.setApiId(api.getId());

    AgentCapabilityEntity grant = new AgentCapabilityEntity();
    grant.setAgentId(agentId);
    grant.setCapabilityType(CapabilityType.SERVICE_ACTION);
    grant.setCapabilityRef(action.getId());
    AgentCapabilityRepository capabilities = mock(AgentCapabilityRepository.class);
    when(capabilities.findByAgentIdAndCapabilityType(agentId, CapabilityType.SERVICE_ACTION))
        .thenReturn(List.of(grant));
    ServiceActionRepository actions = mock(ServiceActionRepository.class);
    when(actions.findAllById(List.of(action.getId()))).thenReturn(List.of(action));
    GeneratedToolRepository apis = mock(GeneratedToolRepository.class);
    when(apis.findById(api.getId())).thenReturn(Optional.of(api));
    ConnectionRepository services = mock(ConnectionRepository.class);
    when(services.findById(service.getId())).thenReturn(Optional.of(service));
    runner = mock(ServiceRunner.class);
    provider = new ServiceActionToolProvider(capabilities, actions, apis, services,
        mock(AgentRepository.class), runner);
  }

  @Test
  void aLiveGrantedActionIsATool() {
    assertThat(provider.toolsFor(agentId)).singleElement().satisfies(tool -> {
      assertThat(tool.name()).isEqualTo("inventory__create_invoice");
      assertThat(tool.description()).startsWith("Inventory: Create invoice");
    });
    provider.invoke(agentId, new ToolCall("inventory__create_invoice", Json.obj()));
    verify(runner).runAction(eq(service.getId()), eq(action.getId()), any(), eq("agent"), any());
  }

  @Test
  void aDraftServiceOrDisabledActionIsInvisible() {
    service.setVersionStatus("draft");
    assertThat(provider.toolsFor(agentId)).isEmpty();
    service.setVersionStatus("live");
    action.setEnabled(false);
    assertThat(provider.toolsFor(agentId)).isEmpty();
    provider.invoke(agentId, new ToolCall("inventory__create_invoice", Json.obj()));
    verify(runner, never()).runAction(any(), any(), any(), any(), any());
  }

  @Test
  void anotherAgentHasNoAccess() {
    assertThat(provider.toolsFor(UUID.randomUUID())).isEmpty();
  }
}
