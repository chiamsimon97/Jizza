package com.jizza.procedures.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.common.procedure.ProcedureService;
import com.jizza.common.tool.JsonSchemas;
import com.jizza.common.tool.ToolCall;
import com.jizza.common.tool.ToolProvider;
import com.jizza.common.tool.ToolResult;
import com.jizza.common.tool.ToolSpec;
import com.jizza.common.util.Json;
import com.jizza.persistence.entity.AgentEntity;
import com.jizza.persistence.repository.AgentRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@code propose_workflow} (R34). Specialists get it; the orchestrator does not, because it is the
 * agent that did the work that knows what the steps were.
 *
 * <p>The tool description is explicit that proposing is not doing: the agent submits a draft and
 * stops. Nothing here approves anything.
 */
@Component
public class ProposeWorkflowToolProvider implements ToolProvider {

  public static final String PROPOSE_WORKFLOW = "propose_workflow";

  private static final String GRAPH_HELP = """
      The workflow as {"nodes":[...],"edges":[...]}.

      A node is {"id":"unique_id","type":"...","label":"human label","config":{...}} where type is
      one of: trigger (exactly one, the entry point), connector_tool
      (config {"toolId":"<generated tool id>","arguments":{...}}), agent_step
      (config {"agentId":"<agent id>","instruction":"..."}), transform
      (config {"expression":"<JEXL over input, nodes and instructions>"}), branch
      (config {"condition":"<JEXL predicate>"}, with edges branch "true" and "false"),
      human_intervention (config {"prompt":"what to ask"}), end.

      An edge is {"from":"node_id","to":"node_id","branch":null}.
      """;

  private final ProcedureService procedures;
  private final AgentRepository agents;
  private final ToolSpec spec;

  public ProposeWorkflowToolProvider(ProcedureService procedures, AgentRepository agents) {
    this.procedures = procedures;
    this.agents = agents;
    this.spec = new ToolSpec(PROPOSE_WORKFLOW,
        "Capture a job you have just done as a reusable workflow and submit it for the user's "
            + "review. It does not run and you must not act on it until the user approves it.",
        JsonSchemas.object(new LinkedHashMap<>(Map.of(
            "name", JsonSchemas.string("Short name for the procedure"),
            "description", JsonSchemas.string("What it does, in one or two sentences"),
            "source_task_id", JsonSchemas.string(
                "The repeated task this procedure came from, if any"),
            "graph", JsonSchemas.objectSchema(GRAPH_HELP))),
            List.of("name", "graph")));
  }

  @Override
  public List<ToolSpec> toolsFor(UUID agentId) {
    return isSpecialist(agentId) ? List.of(spec) : List.of();
  }

  @Override
  public boolean supports(UUID agentId, String toolName) {
    return PROPOSE_WORKFLOW.equals(toolName) && isSpecialist(agentId);
  }

  @Override
  public ToolResult invoke(UUID agentId, ToolCall call) {
    JsonNode args = call.args() == null ? Json.obj() : call.args();
    UUID sourceTaskId = null;
    if (args.hasNonNull("source_task_id") && !args.path("source_task_id").asText().isBlank()) {
      try {
        sourceTaskId = UUID.fromString(args.path("source_task_id").asText());
      } catch (IllegalArgumentException e) {
        return ToolResult.error("'source_task_id' is not a task id");
      }
    }
    try {
      var proposed = procedures.propose(agentId, sourceTaskId, args.path("name").asText(),
          args.path("description").asText(null), args.path("graph"));
      return ToolResult.ok(Json.obj()
          .put("workflow_id", proposed.id().toString())
          .put("version", proposed.version())
          .put("status", proposed.status().name().toLowerCase(java.util.Locale.ROOT))
          .put("note", "Submitted for the user's review. Do not act on it until it is approved."));
    } catch (RuntimeException e) {
      return ToolResult.error(e.getMessage());
    }
  }

  private boolean isSpecialist(UUID agentId) {
    return agents.findById(agentId).map(a -> !a.isOrchestrator()).orElse(false);
  }
}
