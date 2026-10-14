package com.jizza.engine.connectors.tools.cosmos;

import com.jizza.engine.api.cosmos.BodyType;
import com.jizza.engine.api.cosmos.CelestialBody;
import com.jizza.engine.api.cosmos.CosmosAgent;
import com.jizza.engine.api.cosmos.CosmosBodyProvider;
import com.jizza.engine.api.enums.CapabilityType;
import com.jizza.engine.persistence.entity.AgentCapabilityEntity;
import com.jizza.engine.persistence.entity.ConnectionEntity;
import com.jizza.engine.persistence.repository.AgentCapabilityRepository;
import com.jizza.engine.persistence.repository.ConnectionRepository;
import com.jizza.engine.persistence.repository.GeneratedToolRepository;
import com.jizza.engine.persistence.repository.ServiceActionRepository;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The Connectors an agent can use, as moons of its planet. A connection belongs to the user, not
 * to an agent; an agent reaches it through the raw APIs or actions granted to it, so a connector
 * granted to two agents is a moon of both.
 *
 * <p>A moon carries the service's name and connection state, and nothing else from the row: no
 * credential, token, client id, URL or configuration value ever reaches the Cosmos.
 */
@Component
public class ConnectorBodyProvider implements CosmosBodyProvider {

  private final AgentCapabilityRepository capabilities;
  private final GeneratedToolRepository tools;
  private final ServiceActionRepository actions;
  private final ConnectionRepository connections;

  public ConnectorBodyProvider(AgentCapabilityRepository capabilities, GeneratedToolRepository tools,
                               ServiceActionRepository actions, ConnectionRepository connections) {
    this.capabilities = capabilities;
    this.tools = tools;
    this.actions = actions;
    this.connections = connections;
  }

  @Override
  public List<CelestialBody> bodies(UUID userId, CosmosAgent agent) {
    Set<UUID> connectionIds = new LinkedHashSet<>();
    List<AgentCapabilityEntity> granted = capabilities.findByAgentId(agent.id());
    List<UUID> toolIds = refs(granted, CapabilityType.GENERATED_TOOL);
    List<UUID> actionIds = refs(granted, CapabilityType.SERVICE_ACTION);
    if (!toolIds.isEmpty()) tools.findAllById(toolIds).forEach(t -> connectionIds.add(t.getConnectionId()));
    if (!actionIds.isEmpty()) actions.findAllById(actionIds).forEach(a -> connectionIds.add(a.getConnectionId()));
    if (connectionIds.isEmpty()) return List.of();

    List<CelestialBody> out = new ArrayList<>();
    for (ConnectionEntity c : connections.findAllById(connectionIds)) {
      if (!c.getUserId().equals(userId)) continue;
      out.add(new CelestialBody(bodyId(c.getId(), agent.id()), BodyType.CONNECTOR, agent.bodyId(),
          c.getServiceName(), agent.key(), 0, 0, 0, null,
          Map.of("connectionId", c.getId().toString(),
              "serviceName", c.getServiceName(),
              "status", String.valueOf(c.getStatus()).toLowerCase(Locale.ROOT))));
    }
    return out;
  }

  /** A connector shared by several agents is one moon per agent; the agent id keeps them apart. */
  public static String bodyId(UUID connectionId, UUID agentId) {
    return BodyType.CONNECTOR.name() + ":" + connectionId + ":" + agentId;
  }

  private static List<UUID> refs(List<AgentCapabilityEntity> granted, CapabilityType type) {
    return granted.stream().filter(g -> g.getCapabilityType() == type)
        .map(AgentCapabilityEntity::getCapabilityRef).toList();
  }
}
