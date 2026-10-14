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

  private final MemoryProperties props;

  public MemoryScopes(MemoryProperties props) {
    this.props = props;
  }

  /**
   * A specialist sees shared facts and its own domain's. The orchestrator sees the shared layer,
   * or everything when {@code jizza.memory.orchestrator-sees-all-domains} is on. An agent with no
   * domain sees shared facts only.
   */
  public MemoryScope forAgent(AgentEntity agent) {
    if (agent.isOrchestrator()) {
      return props.isOrchestratorSeesAllDomains() ? MemoryScope.everything() : MemoryScope.sharedOnly();
    }
    String domain = agent.getDomain();
    return domain == null || domain.isBlank()
        ? MemoryScope.sharedOnly()
        : MemoryScope.of(List.of(domain.toLowerCase(Locale.ROOT)));
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
