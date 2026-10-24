package com.jizza.engine.procedures.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.procedure.ProcedureService;
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
import java.util.Locale;
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

      A node is {"id":"unique_id","type":"TYPE","label":"short human label","config":{...}}.
      Build it from built-in steps, which run the same way every time. Use AGENT_STEP only where
      the step really needs judgement on free text, and APPROVAL before anything the user would
      want to OK first.

      Start and finish:
        TRIGGER (exactly one, the entry point), END.
        A workflow meant to be reused as a module declares what it takes and gives:
        TRIGGER {"inputs":[{"name":"email","type":"text|number|boolean|date|list|map",
          "required":true,"description":"...","default":...}]}, read as input.<name>;
        END {"outputs":[{"name":"total","type":"number","value":"{{ nodes.sum }}"}]}.
      Actions:
        CONNECTOR_TOOL {"toolId":"<generated tool id>","arguments":{...}}
        MCP_TOOL {"serverId":"<MCP server id>","tool":"<tool name>","arguments":{...}}
        HTTP_REQUEST {"url":"https://...","method":"POST","bodyType":"json","fields":{...}}
        NOTIFY {"message":"...","title":"...","channel":"app|email|both"}
        AGENT_STEP {"agentId":"<agent id>","instruction":"..."}
      Logic:
        FILTER {"condition":"<expression>"} carries on only when true.
        BRANCH {"condition":"<expression>"} with edges branch "true" and "false".
        SWITCH {"cases":[{"id":"a","label":"...","condition":"<expression>"}]} with an edge per
          case id, plus an optional "else" edge.
        APPROVAL {"prompt":"what to approve"} with edges branch "approved" and "rejected".
        HUMAN_INTERVENTION {"prompt":"what to ask"} when only the user can decide.
      Data:
        SET_VARIABLE {"variables":[{"name":"total","expr":"<expression>"}]}
        STORAGE {"action":"get|set|increment|append|delete","key":"...","valueExpr":"..."}
          keeps values between runs.
        TRANSFORM {"expression":"<expression>"}
        SCRIPT {"inputs":[{"name":"lines","type":"list","value":"{{ nodes.fetch.body.lines }}"}],
          "script":"var total = 0;\\nfor (var l : lines) { total = total + l.amount; }\\nreturn {'total': total};",
          "outputs":[{"name":"total","type":"number"}]} for logic a formula can't hold: var, if/else,
          for over a list (no while), return a map; it reads only its inputs, by name. Later steps
          read nodes.<id>.<output name>.
      Flow:
        WAIT {"duration":"PT1H"} or {"until":"<ISO time>"}
        PARALLEL then JOIN to run paths at once,
        SUB_WORKFLOW {"workflowId":"...","inputs":{"<input name>":"{{ ... }}"}} runs a module;
          later steps read its outputs as nodes.<id>.<output name>.
        FOR_EACH {"workflowId":"...","items":"<expression giving a list>"}.

      Expressions are Jizza formulas over input (what started the run), nodes.<id> (an earlier
      step's result) and vars (SET_VARIABLE), with functions chained on a value:
      input.name.trim().titleCase(), items.where('status', 'paid').sumOf('amount').
      Text fields can embed the same with {{ nodes.fetch.total }}.

      An edge is {"from":"node_id","to":"node_id","branch":null}. No loops: repeat with FOR_EACH.
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
          args.path("description").asText(null), normalizeTypes(args.path("graph")));
      return ToolResult.ok(Json.obj()
          .put("workflow_id", proposed.id().toString())
          .put("version", proposed.version())
          .put("status", proposed.status().name().toLowerCase(java.util.Locale.ROOT))
          .put("note", "Submitted for the user's review. Do not act on it until it is approved."));
    } catch (RuntimeException e) {
      return ToolResult.error(e.getMessage());
    }
  }

  /** Agents often write step types in lower case ("trigger"); the graph stores them upper case. */
  public static JsonNode normalizeTypes(JsonNode graph) {
    if (graph == null || !graph.path("nodes").isArray()) return graph;
    JsonNode copy = graph.deepCopy();
    for (JsonNode node : copy.path("nodes")) {
      if (node instanceof ObjectNode object && node.path("type").isTextual()) {
        object.put("type", node.path("type").asText().trim().toUpperCase(Locale.ROOT));
      }
    }
    return copy;
  }

  private boolean isSpecialist(UUID agentId) {
    return agents.findById(agentId).map(a -> !a.isOrchestrator()).orElse(false);
  }
}
