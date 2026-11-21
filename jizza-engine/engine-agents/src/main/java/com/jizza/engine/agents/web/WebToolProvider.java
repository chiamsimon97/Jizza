package com.jizza.engine.agents.web;

import com.jizza.engine.agents.config.AgentProperties;
import com.jizza.engine.api.autonomy.ActionTypeSource;
import com.jizza.engine.api.enums.ActionType;
import com.jizza.engine.api.tool.JsonSchemas;
import com.jizza.engine.api.tool.ToolCall;
import com.jizza.engine.api.tool.ToolProvider;
import com.jizza.engine.api.tool.ToolResult;
import com.jizza.engine.api.tool.ToolSpec;
import com.jizza.engine.persistence.entity.AgentEntity;
import com.jizza.engine.persistence.repository.AgentRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Searching the web and reading a page, with the model provider's own tools: the provider runs
 * them and returns the pages inside the reply, so nothing here ever executes one. Offered to every
 * agent whose owner left web access on, and withheld everywhere when it is switched off in config.
 * Both only read, so the guardrails treat them as reads.
 */
@Component
public class WebToolProvider implements ToolProvider, ActionTypeSource {

  static final ToolSpec SEARCH = new ToolSpec(ToolSpec.WEB_SEARCH,
      "Search the web for current information, with the pages found as sources.",
      JsonSchemas.emptyObject());
  static final ToolSpec FETCH = new ToolSpec(ToolSpec.WEB_FETCH,
      "Read a web page whose link is already in the conversation.", JsonSchemas.emptyObject());

  private final AgentRepository agents;
  private final AgentProperties props;

  public WebToolProvider(AgentRepository agents, AgentProperties props) {
    this.agents = agents;
    this.props = props;
  }

  /** Web tools are switched on for this deployment. */
  public boolean available() {
    return props.getAnthropic().getWeb().isEnabled();
  }

  @Override
  public List<ToolSpec> toolsFor(UUID agentId) {
    if (!available()) return List.of();
    boolean allowed = agents.findById(agentId).map(AgentEntity::isWebAccess).orElse(false);
    return allowed ? List.of(SEARCH, FETCH) : List.of();
  }

  /** The provider answers these calls itself, so none ever comes here to run. */
  @Override
  public boolean supports(UUID agentId, String toolName) {
    return false;
  }

  @Override
  public ToolResult invoke(UUID agentId, ToolCall call) {
    return ToolResult.error("Web search runs with the model provider, not here.");
  }

  @Override
  public Optional<ActionType> actionTypeOf(UUID agentId, String toolRef) {
    return ToolSpec.WEB_SEARCH.equals(toolRef) || ToolSpec.WEB_FETCH.equals(toolRef)
        ? Optional.of(ActionType.READ) : Optional.empty();
  }
}
