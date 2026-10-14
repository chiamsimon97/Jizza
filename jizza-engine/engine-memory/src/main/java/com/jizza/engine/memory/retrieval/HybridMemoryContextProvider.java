package com.jizza.engine.memory.retrieval;

import com.jizza.engine.api.memory.MemoryContextProvider;
import com.jizza.engine.api.memory.MemoryScope;
import com.jizza.engine.memory.config.MemoryProperties;
import com.jizza.engine.memory.config.MemoryScopes;
import com.jizza.engine.persistence.entity.AgentEntity;
import com.jizza.engine.persistence.entity.ConnectionEntity;
import com.jizza.engine.persistence.entity.MemorySubjectEntity;
import com.jizza.engine.persistence.repository.AgentRepository;
import com.jizza.engine.persistence.repository.ConnectionRepository;
import com.jizza.engine.api.cosmos.MemoryAccessRecorder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The memory an agent starts each turn with: hybrid retrieval over what this agent may see,
 * rendered grouped by subject within a character budget.
 *
 * <p>Works the same for Jizza and for a specialist it delegated to. The specialist's "request" is
 * the task it was handed, which names what it is about ("rebalance my wife's portfolio"), so the
 * specialist gets the facts about her it is allowed to see without anyone having to pass them on.
 */
@Service
public class HybridMemoryContextProvider implements MemoryContextProvider {

  private static final Logger log = LoggerFactory.getLogger(HybridMemoryContextProvider.class);

  private final HybridSearch search;
  private final AgentRepository agents;
  private final ConnectionRepository connections;
  private final MemoryAccessRecorder recorder;
  private final MemoryScopes scopes;
  private final MemoryProperties props;

  public HybridMemoryContextProvider(HybridSearch search, AgentRepository agents,
                                     ConnectionRepository connections, MemoryAccessRecorder recorder,
                                     MemoryScopes scopes, MemoryProperties props) {
    this.search = search;
    this.agents = agents;
    this.connections = connections;
    this.recorder = recorder;
    this.scopes = scopes;
    this.props = props;
  }

  @Override
  public MemoryContext contextFor(UUID userId, UUID agentId, String request) {
    return forAgent(userId, agentId, request, true);
  }

  @Override
  public MemoryContext preview(UUID userId, UUID agentId, String request) {
    return forAgent(userId, agentId, request, false);
  }

  private MemoryContext forAgent(UUID userId, UUID agentId, String request, boolean touch) {
    if (!props.isEnabled()) return MemoryContext.EMPTY;
    AgentEntity agent = agents.findById(agentId).orElse(null);
    if (agent == null || !agent.getUserId().equals(userId)) return MemoryContext.EMPTY;
    MemoryContext context = contextFor(userId, scopes.forAgent(agent), request);
    if (touch && !context.isEmpty()) {
      // A stamp in each fact's passport, for this agent and this run.
      try {
        recorder.facts(userId, agentId, context.memoryIds());
      } catch (RuntimeException e) {
        log.debug("Could not record memory access", e);
      }
    }
    return context;
  }

  MemoryContext contextFor(UUID userId, MemoryScope scope, String request) {
    MemoryProperties.Retrieval cfg = props.getRetrieval();
    HybridSearch.Result result = search.rank(userId, request, scope, cfg.getMaxItems(), true);
    if (result.ranked().isEmpty() && result.resolution().direct().isEmpty()) return MemoryContext.EMPTY;

    Set<UUID> primary = new LinkedHashSet<>(result.resolution().direct());
    MemoryContextRenderer.Rendered rendered = MemoryContextRenderer.render(result.ranked(),
        result.subjects(), result.relations(), linkedConnections(userId, result.subjects()), primary,
        cfg.getMaxChars());
    if (rendered.text().isEmpty()) return MemoryContext.EMPTY;

    List<UUID> subjectIds = new ArrayList<>(result.resolution().direct());
    subjectIds.addAll(result.resolution().related());
    return new MemoryContext(rendered.text(), rendered.memoryIds(), List.copyOf(subjectIds));
  }

  private Map<UUID, MemoryContextRenderer.LinkedConnection> linkedConnections(
      UUID userId, Map<UUID, MemorySubjectEntity> subjects) {
    Set<UUID> ids = new LinkedHashSet<>();
    subjects.values().stream().map(MemorySubjectEntity::getConnectionId).filter(Objects::nonNull).forEach(ids::add);
    if (ids.isEmpty()) return Map.of();
    Map<UUID, MemoryContextRenderer.LinkedConnection> out = new HashMap<>();
    for (ConnectionEntity c : connections.findAllById(ids)) {
      if (!c.getUserId().equals(userId)) continue;
      out.put(c.getId(), new MemoryContextRenderer.LinkedConnection(c.getServiceName(),
          String.valueOf(c.getStatus()).toLowerCase(Locale.ROOT)));
    }
    return out;
  }
}
