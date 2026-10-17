package com.jizza.engine.orchestrator.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.enums.ActionType;
import com.jizza.engine.api.orchestrator.UiAction;
import com.jizza.engine.api.tool.JsonSchemas;
import com.jizza.engine.api.tool.ToolCall;
import com.jizza.engine.api.tool.ToolProvider;
import com.jizza.engine.api.tool.ToolResult;
import com.jizza.engine.api.tool.ToolSpec;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.persistence.entity.AgentEntity;
import com.jizza.engine.persistence.repository.AgentRepository;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * {@code app_propose_agent}: Jizza drafts a new agent, or a whole team, for the user to review.
 *
 * <p>Like {@code app_propose_workflow} it changes no data. The draft is recorded as a
 * {@link UiAction} on the reply and shown as a card; creating the agent is the user's own action
 * from that card, through the same checks as the form. Whatever modes the draft asks for, the card
 * shows them against the safe profile, and the platform enforces locks when it is saved.
 */
@Component
public class AgentProposalToolProvider implements ToolProvider {

  public static final String PROPOSE = "app_propose_agent";

  static final int MAX_AGENTS = 16;

  private final AgentRepository agents;
  private final UiActionRecorder recorder;

  public AgentProposalToolProvider(AgentRepository agents, UiActionRecorder recorder) {
    this.agents = agents;
    this.recorder = recorder;
  }

  @Override
  public List<ToolSpec> toolsFor(UUID agentId) {
    return orchestrator(agentId).map(jizza -> List.of(spec(managers(jizza.getUserId()))))
        .orElse(List.of());
  }

  @Override
  public boolean supports(UUID agentId, String toolName) {
    return PROPOSE.equals(toolName) && orchestrator(agentId).isPresent();
  }

  @Override
  public ToolResult invoke(UUID agentId, ToolCall call) {
    Optional<AgentEntity> jizza = orchestrator(agentId);
    if (jizza.isEmpty()) return ToolResult.error("Only Jizza may propose a new agent");
    if (!recorder.active()) {
      return ToolResult.error("The user is not looking at the app right now, so there is nowhere "
          + "to show the draft. Describe the agent in text instead.");
    }
    UUID userId = jizza.get().getUserId();
    JsonNode args = call.args() == null ? Json.obj() : call.args();
    JsonNode drafts = args.path("agents");
    if (!drafts.isArray() || drafts.isEmpty()) {
      return ToolResult.error("'agents' must list at least one agent");
    }
    if (drafts.size() > MAX_AGENTS) {
      return ToolResult.error("Propose at most " + MAX_AGENTS + " agents at once");
    }

    AgentEntity parent = jizza.get();
    String under = args.path("reports_to_key").asText("").trim();
    if (!under.isEmpty() && !under.equals(parent.getKey())) {
      Optional<AgentEntity> found = agents.findByUserIdAndKey(userId, under)
          .filter(a -> !"archived".equals(a.getStatus()));
      if (found.isEmpty()) {
        return ToolResult.error("No agent with key '" + under + "'. You can put the new agent "
            + "under: " + managers(userId));
      }
      parent = found.get();
    }

    Set<String> names = new HashSet<>();
    for (AgentEntity existing : agents.findByUserIdOrderByIsOrchestratorDescNameAsc(userId)) {
      if (!"archived".equals(existing.getStatus())) names.add(existing.getName().toLowerCase(Locale.ROOT));
    }
    Set<String> refs = new HashSet<>();
    ObjectNode payload = Json.obj();
    payload.put("proposalId", UUID.randomUUID().toString());
    payload.put("parentAgentId", parent.getId().toString());
    payload.put("parentAgentName", parent.getName());
    if (!args.path("summary").asText("").isBlank()) {
      payload.put("summary", clip(args.path("summary").asText(), 300));
    }
    ArrayNode out = payload.putArray("agents");
    int i = 0;
    for (JsonNode d : drafts) {
      i++;
      String name = d.path("name").asText("").trim();
      String description = d.path("description").asText("").trim();
      String instructions = d.path("instructions").asText("").trim();
      if (name.isEmpty() || name.length() > 60) {
        return ToolResult.error("Agent " + i + " needs a name of at most 60 characters");
      }
      if (name.equalsIgnoreCase("jizza") || !names.add(name.toLowerCase(Locale.ROOT))) {
        return ToolResult.error("The user already has an agent called " + name
            + "; pick another name");
      }
      if (description.isEmpty() || description.length() > 300) {
        return ToolResult.error(name + " needs a 'when to use me' of at most 300 characters");
      }
      if (instructions.isEmpty() || instructions.length() > 4000) {
        return ToolResult.error(name + " needs instructions of at most 4000 characters");
      }
      String ref = d.path("ref").asText("a" + i).trim();
      if (!refs.add(ref)) return ToolResult.error("Two agents share the ref " + ref);
      String reportsTo = d.path("reports_to").asText("").trim();
      if (!reportsTo.isEmpty() && !refs.contains(reportsTo)) {
        return ToolResult.error(name + " reports to '" + reportsTo + "', which must be the ref of "
            + "an agent listed before it");
      }

      ObjectNode agent = out.addObject();
      agent.put("ref", ref).put("name", name).put("description", description)
          .put("instructions", instructions)
          .put("modelTier", "deep".equals(d.path("model_tier").asText()) ? "deep" : "standard");
      if (!reportsTo.isEmpty()) agent.put("reportsTo", reportsTo);
      agent.set("responsibilities", strings(d.path("responsibilities"), 8, 120));
      agent.set("toolHints", strings(d.path("tool_hints"), 8, 40));
      ArrayNode domains = agent.putArray("memoryDomains");
      for (JsonNode v : d.path("memory_domains")) {
        String domain = v.asText().toLowerCase(Locale.ROOT);
        if (List.of("finance", "health", "professional").contains(domain)) domains.add(domain);
      }
      ObjectNode modes = agent.putObject("modes");
      ObjectNode stops = agent.putObject("stops");
      for (ActionType type : ActionType.values()) {
        String asked = d.path("modes").path(type.name().toLowerCase(Locale.ROOT)).asText("");
        String mode = switch (asked) {
          case "auto", "allow" -> "ALLOW";
          case "ask" -> "ASK";
          case "never", "deny" -> "DENY";
          default -> null;
        };
        if (mode != null) modes.put(type.name(), mode);
        stops.put(type.name(), mode != null ? mode : profile(type));
      }
      ArrayNode locks = agent.putArray("locks");
      for (JsonNode v : d.path("locks")) {
        try {
          locks.add(ActionType.valueOf(v.asText().toUpperCase(Locale.ROOT)).name());
        } catch (IllegalArgumentException ignored) {
          // Not a kind of action; left out.
        }
      }
      agent.set("avatar", Json.obj().put("style", "spark")
          .put("colour", COLOURS.get((i - 1) % COLOURS.size())));
    }

    recorder.record(new UiAction(UiAction.PROPOSE_AGENT, payload));
    return ToolResult.ok(Json.obj().put("done", true).put("note",
        "The draft is on the user's screen. They can create it as it is, change it in the form, "
            + "or dismiss it; nothing exists until they do. End your turn with one or two "
            + "sentences; do not repeat the draft."));
  }

  private static final List<String> COLOURS = List.of("teal", "purple", "coral", "blue", "amber",
      "green", "pink");

  /** The safe profile a new agent starts from, as the card shows it. */
  private static String profile(ActionType type) {
    return switch (type) {
      case READ, DRAFT -> "ALLOW";
      case MONEY -> "DENY";
      default -> "ASK";
    };
  }

  private static ToolSpec spec(String managers) {
    ObjectNode modes = JsonSchemas.object(new LinkedHashMap<>(Map.of(
        "send", JsonSchemas.string("auto, ask or never"),
        "money", JsonSchemas.string("auto, ask or never"),
        "create", JsonSchemas.string("auto, ask or never"),
        "update", JsonSchemas.string("auto, ask or never"),
        "delete", JsonSchemas.string("auto, ask or never"))), List.of());
    Map<String, JsonNode> props = new LinkedHashMap<>();
    props.put("ref", JsonSchemas.string("A short id for this agent inside the proposal, e.g. pm"));
    props.put("name", JsonSchemas.string("What the user will call it, e.g. Wanda"));
    props.put("description", JsonSchemas.string(
        "When to use it, in one sentence: what its manager reads when choosing who to ask"));
    props.put("instructions", JsonSchemas.string(
        "How it works: its job, tone, and what it must always or never do"));
    props.put("reports_to", JsonSchemas.string(
        "For a team: the ref of its manager in this proposal. Leave out for the team's lead"));
    props.put("responsibilities", stringArray("Up to 8 short responsibilities"));
    props.put("tool_hints", stringArray("Words for the tools it will need, e.g. flights, email"));
    props.put("memory_domains", stringArray(
        "Shared memory it needs to read: finance, health or professional. Usually none"));
    props.put("modes", modes);
    props.put("locks", stringArray("Kinds of action whose mode caps everyone below it"));
    props.put("model_tier", JsonSchemas.string("standard, or deep for hard reasoning"));
    ObjectNode agent = JsonSchemas.object(new LinkedHashMap<>(props),
        List.of("name", "description", "instructions"));
    ObjectNode list = Json.obj().put("type", "array")
        .put("description", "One agent, or a team with its lead first");
    list.set("items", agent);
    return new ToolSpec(PROPOSE,
        "Show the user a draft of a new agent, or a small team of agents, to create. Use it when "
            + "the user asks for an agent of their own, or when no one on their team fits work "
            + "they want done regularly. Keep teams shallow: add a level only when it splits the "
            + "work or needs tools the level above shouldn't have. Reading and drafting are "
            + "automatic, sending and changing things ask, and moving money is never allowed "
            + "unless you set otherwise. The user creates it from the card; you never do.",
        JsonSchemas.object(new LinkedHashMap<>(Map.of(
            "reports_to_key", JsonSchemas.string(
                "Who the new agent (or team lead) reports to. Leave out for you. One of: "
                    + managers),
            "summary", JsonSchemas.string("One sentence on what this is for"),
            "agents", list)), List.of("agents")));
  }

  private static ObjectNode stringArray(String description) {
    ObjectNode node = Json.obj().put("type", "array").put("description", description);
    node.set("items", Json.obj().put("type", "string"));
    return node;
  }

  private String managers(UUID userId) {
    return agents.findByUserIdOrderByIsOrchestratorDescNameAsc(userId).stream()
        .filter(a -> !a.isOrchestrator() && "active".equals(a.getStatus()))
        .map(a -> a.getKey() + " (" + a.getName() + ")")
        .collect(Collectors.joining(", "));
  }

  private Optional<AgentEntity> orchestrator(UUID agentId) {
    return agents.findById(agentId).filter(AgentEntity::isOrchestrator);
  }

  private static ArrayNode strings(JsonNode values, int max, int maxLength) {
    ArrayNode out = Json.mapper().createArrayNode();
    for (JsonNode v : values) {
      if (out.size() == max) break;
      String s = v.asText("").trim();
      if (!s.isEmpty()) out.add(clip(s, maxLength));
    }
    return out;
  }

  private static String clip(String s, int max) {
    String t = s.trim();
    return t.length() > max ? t.substring(0, max) : t;
  }
}
