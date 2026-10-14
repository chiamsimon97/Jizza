package com.jizza.engine.connectors.tools.cosmos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jizza.engine.api.cosmos.BodyType;
import com.jizza.engine.api.cosmos.CelestialBody;
import com.jizza.engine.api.cosmos.CosmosAgent;
import com.jizza.engine.api.enums.AuthScheme;
import com.jizza.engine.api.enums.CapabilityType;
import com.jizza.engine.api.enums.ConnectionStatus;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.persistence.entity.AgentCapabilityEntity;
import com.jizza.engine.persistence.entity.ConnectionEntity;
import com.jizza.engine.persistence.entity.GeneratedToolEntity;
import com.jizza.engine.persistence.repository.AgentCapabilityRepository;
import com.jizza.engine.persistence.repository.ConnectionRepository;
import com.jizza.engine.persistence.repository.GeneratedToolRepository;
import com.jizza.engine.persistence.repository.ServiceActionRepository;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConnectorBodyProviderTest {

  private final UUID userId = UUID.randomUUID();
  private final UUID agentId = UUID.randomUUID();
  private final CosmosAgent kai = new CosmosAgent(agentId, "finance", "Kai", "finance", false,
      "AGENT:" + agentId);

  @Test
  void aConnectorMoonCarriesItsNameAndStateAndNoSecretOrConfiguration() {
    ConnectionEntity zerodha = new ConnectionEntity();
    zerodha.setId(UUID.randomUUID());
    zerodha.setUserId(userId);
    zerodha.setServiceName("Zerodha");
    zerodha.setAuthScheme(AuthScheme.OAUTH2);
    zerodha.setStatus(ConnectionStatus.CONNECTED);
    zerodha.setClientId("client-id-value");
    zerodha.setClientSecret("client-secret-value".getBytes(StandardCharsets.UTF_8));
    zerodha.setAccessToken("access-token-value".getBytes(StandardCharsets.UTF_8));
    zerodha.setRefreshToken("refresh-token-value".getBytes(StandardCharsets.UTF_8));
    zerodha.setAuthorizationUrl("https://kite.example.com/authorize");
    zerodha.setAccessTokenUrl("https://kite.example.com/token");
    zerodha.setBaseUrl("https://api.kite.example.com");
    zerodha.setScopes("holdings orders");
    zerodha.setExtraFields(Json.obj().put("apiSecret", "extra-field-value"));

    GeneratedToolEntity tool = new GeneratedToolEntity();
    tool.setId(UUID.randomUUID());
    tool.setConnectionId(zerodha.getId());
    AgentCapabilityEntity grant = new AgentCapabilityEntity();
    grant.setAgentId(agentId);
    grant.setCapabilityType(CapabilityType.GENERATED_TOOL);
    grant.setCapabilityRef(tool.getId());

    AgentCapabilityRepository capabilities = mock(AgentCapabilityRepository.class);
    GeneratedToolRepository tools = mock(GeneratedToolRepository.class);
    ServiceActionRepository actions = mock(ServiceActionRepository.class);
    ConnectionRepository connections = mock(ConnectionRepository.class);
    when(capabilities.findByAgentId(agentId)).thenReturn(List.of(grant));
    when(tools.findAllById(any())).thenReturn(List.of(tool));
    when(connections.findAllById(any())).thenReturn(List.of(zerodha));

    List<CelestialBody> bodies = new ConnectorBodyProvider(capabilities, tools, actions, connections)
        .bodies(userId, kai);

    assertThat(bodies).singleElement().satisfies(b -> {
      assertThat(b.type()).isEqualTo(BodyType.CONNECTOR);
      assertThat(b.parentBodyId()).isEqualTo(kai.bodyId());
      assertThat(b.label()).isEqualTo("Zerodha");
      assertThat(b.meta()).containsOnlyKeys("connectionId", "serviceName", "status");
      assertThat(b.meta().get("status")).isEqualTo("connected");
    });
    String everything = Json.write(bodies);
    for (String secret : List.of("client-id-value", "client-secret-value", "access-token-value",
        "refresh-token-value", "kite.example.com", "holdings", "extra-field-value")) {
      assertThat(everything).doesNotContain(secret);
    }
  }

  @Test
  void anotherUsersConnectionIsNeverAMoon() {
    ConnectionEntity theirs = new ConnectionEntity();
    theirs.setId(UUID.randomUUID());
    theirs.setUserId(UUID.randomUUID());
    theirs.setServiceName("Theirs");
    AgentCapabilityEntity grant = new AgentCapabilityEntity();
    grant.setAgentId(agentId);
    grant.setCapabilityType(CapabilityType.SERVICE_ACTION);
    grant.setCapabilityRef(UUID.randomUUID());

    AgentCapabilityRepository capabilities = mock(AgentCapabilityRepository.class);
    ServiceActionRepository actions = mock(ServiceActionRepository.class);
    ConnectionRepository connections = mock(ConnectionRepository.class);
    var action = mock(com.jizza.engine.persistence.entity.ServiceActionEntity.class);
    when(action.getConnectionId()).thenReturn(theirs.getId());
    when(capabilities.findByAgentId(agentId)).thenReturn(List.of(grant));
    when(actions.findAllById(any())).thenReturn(List.of(action));
    when(connections.findAllById(any())).thenReturn(List.of(theirs));

    assertThat(new ConnectorBodyProvider(capabilities, mock(GeneratedToolRepository.class), actions,
        connections).bodies(userId, kai)).isEmpty();
  }
}
