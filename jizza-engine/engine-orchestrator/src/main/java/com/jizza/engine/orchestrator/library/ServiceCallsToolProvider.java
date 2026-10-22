package com.jizza.engine.orchestrator.library;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.tool.JsonSchemas;
import com.jizza.engine.api.tool.ToolCall;
import com.jizza.engine.api.tool.ToolProvider;
import com.jizza.engine.api.tool.ToolResult;
import com.jizza.engine.api.tool.ToolSpec;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.persistence.entity.AgentEntity;
import com.jizza.engine.persistence.repository.AgentRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@code recent_service_calls}: the service calls this conversation's jobs made, so Jizza can
 * offer to save a job as the exact steps that did it.
 *
 * <p>Jizza never sees a specialist's calls otherwise: specialists answer it in words. Read-only;
 * arguments a provider keeps hidden stay hidden here too.
 */
@Component
public class ServiceCallsToolProvider implements ToolProvider {

  public static final String RECENT = "recent_service_calls";

  private final AgentRepository agents;
  private final CallSteps calls;
  private final ToolSpec spec = new ToolSpec(RECENT,
      "List the service calls made in this conversation, with the arguments they were made "
          + "with. Use it before offering to save a job as a workflow: each call marked as_step "
          + "can go into app_propose_workflow as a tool step that makes the same call every run.",
      JsonSchemas.object(new LinkedHashMap<>(), List.of()));

  public ServiceCallsToolProvider(AgentRepository agents, CallSteps calls) {
    this.agents = agents;
    this.calls = calls;
  }

  @Override
  public List<ToolSpec> toolsFor(UUID agentId) {
    return orchestrator(agentId).isPresent() ? List.of(spec) : List.of();
  }

  @Override
  public boolean supports(UUID agentId, String toolName) {
    return RECENT.equals(toolName) && orchestrator(agentId).isPresent();
  }

  @Override
  public ToolResult invoke(UUID agentId, ToolCall call) {
    Optional<AgentEntity> jizza = orchestrator(agentId);
    if (jizza.isEmpty()) return ToolResult.error("Only Jizza may list the conversation's calls");
    List<CallSteps.Listed> recent = calls.recent(jizza.get().getUserId());
    ObjectNode out = Json.obj();
    ArrayNode list = out.putArray("calls");
    for (CallSteps.Listed l : recent) {
      ObjectNode node = list.addObject().put("call", l.number()).put("tool", l.call().tool())
          .put("by", l.call().agentName()).put("as_step", l.action().isPresent());
      if (l.call().arguments() == null) node.put("arguments", "(hidden)");
      else node.set("arguments", l.call().arguments());
      l.action().ifPresent(a -> {
        if (a.getLabel() != null) node.put("label", a.getLabel());
        node.put("changes_things", a.isSideEffecting());
      });
    }
    out.put("note", recent.isEmpty()
        ? "No service calls were made in this conversation."
        : "To save the job, use a tool step per call you want repeated, in order: "
            + "{\"kind\":\"tool\",\"call\":<n>,\"inputs\":{\"<argument>\":\"<input name>\"}}. Map "
            + "every value the user will change between runs (a city, a date, a recipient) to an "
            + "input, and leave fixed values as they are. A later step can use an earlier step's "
            + "result with \"arguments\":{\"<argument>\":\"{{ nodes.step_<n> }}\"}. Calls that "
            + "are not as_step need an agent step.");
    return ToolResult.ok(out);
  }

  private Optional<AgentEntity> orchestrator(UUID agentId) {
    return agents.findById(agentId).filter(AgentEntity::isOrchestrator);
  }
}
