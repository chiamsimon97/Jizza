package com.jizza.engine.memory.config;

import com.jizza.engine.api.enums.DataClassification;
import com.jizza.engine.api.enums.MemoryCategory;
import com.jizza.engine.api.memory.MemoryScope;
import com.jizza.engine.persistence.entity.AgentEntity;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * The access rules in one place: which facts an agent may see, and which agents a new fact is
 * visible to by default.
 */
@Component
public class MemoryScopes {

  /** Built-in domains a custom agent may be allowed to read. */
  public static final List<String> BUILT_IN_DOMAINS = List.of("finance", "health", "professional");

  private final MemoryProperties props;
  private com.jizza.engine.persistence.repository.AgentRepository agents;

  public MemoryScopes(MemoryProperties props) {
    this.props = props;
  }

  /** Lets a team member read its manager's memory; without it every agent stands alone. */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setAgents(com.jizza.engine.persistence.repository.AgentRepository agents) {
    this.agents = agents;
  }

  /**
   * A specialist sees shared facts and its own domain's. The orchestrator sees the shared layer,
   * or every domain when {@code jizza.memory.orchestrator-sees-all-domains} is on. An agent with no
   * domain sees shared facts only. No agent sees a restricted fact.
   */
  public MemoryScope forAgent(AgentEntity agent) {
    if (agent.isOrchestrator()) {
      return props.isOrchestratorSeesAllDomains() ? MemoryScope.everyDomain() : MemoryScope.sharedOnly();
    }
    return MemoryScope.of(domainsOf(agent, 0));
  }

  /**
   * A specialist's own domain; for a custom agent also the built-in domains it was allowed, and,
   * when it reads its manager's memory, whatever its manager may read -- never more than that.
   * Jizza's memory is not a manager's to share: it sees only the shared layer either way.
   */
  private List<String> domainsOf(AgentEntity agent, int depth) {
    java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
    String domain = agent.getDomain();
    if (domain != null && !domain.isBlank()) out.add(domain.toLowerCase(Locale.ROOT));
    if (agent.isCustom()) {
      for (String extra : agent.getMemoryDomains()) {
        String d = extra.toLowerCase(Locale.ROOT);
        if (BUILT_IN_DOMAINS.contains(d)) out.add(d);
      }
      if (agent.isMemoryFromManager() && agent.getParentAgentId() != null && agents != null
          && depth < 8) {
        agents.findById(agent.getParentAgentId())
            .filter(manager -> !manager.isOrchestrator())
            .ifPresent(manager -> out.addAll(domainsOf(manager, depth + 1)));
      }
    }
    return List.copyOf(out);
  }

  public List<String> defaultDomains(MemoryCategory category) {
    List<String> domains = props.getCategoryDomains().get(category);
    return domains == null ? List.of() : domains.stream().map(d -> d.toLowerCase(Locale.ROOT)).toList();
  }

  public static DataClassification defaultClassification(MemoryCategory category) {
    return switch (category) {
      case FINANCE -> DataClassification.FINANCIAL;
      case HEALTH -> DataClassification.HEALTH;
      case PROFILE, RELATIONSHIP -> DataClassification.PII;
      default -> DataClassification.NONE;
    };
  }
}
