package com.jizza.engine.orchestrator.workspace;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Jizza's way into every module of the app: {@code workspace_overview}, {@code workspace_search},
 * {@code workspace_list} and {@code workspace_get}. They only read. What Jizza then does with what
 * it found goes through the module's own tool and its guardrails, which the results name.
 */
@Component
public class WorkspaceToolProvider implements ToolProvider {

  public static final String OVERVIEW = "workspace_overview";
  public static final String SEARCH = "workspace_search";
  public static final String LIST = "workspace_list";
  public static final String GET = "workspace_get";

  static final int MAX_LIST = 50;

  private final AgentRepository agents;
  private final Workspace workspace;
  private final List<ToolSpec> specs;

  public WorkspaceToolProvider(AgentRepository agents, Workspace workspace) {
    this.agents = agents;
    this.workspace = workspace;
    List<String> modules = new ArrayList<>();
    for (Workspace.Module m : Workspace.Module.values()) modules.add(m.key);
    this.specs = List.of(
        new ToolSpec(OVERVIEW,
            "What the user has in each part of Jizza (workflows, runs, functions, agents, devices, "
                + "connectors, scheduled tasks, inbox): how many, their names, and which tool acts "
                + "on each. Use it when the user asks what they have or what you can do, or when "
                + "you are not sure which part of the app a request belongs to.",
            JsonSchemas.emptyObject()),
        new ToolSpec(SEARCH,
            "Find things the user has built or set up by what they call them, across all modules "
                + "or the ones given. Matching forgives wording and spelling (\"ramayana movie\" "
                + "finds \"Ramayanam Ticket Booking\"). Use it whenever the user refers to "
                + "something of theirs by a description rather than its exact name.",
            JsonSchemas.object(new LinkedHashMap<>(Map.of(
                "query", JsonSchemas.string("The user's words for it"),
                "modules", stringEnumArray("Only these modules; leave out to search everything",
                    modules))),
                List.of("query"))),
        new ToolSpec(LIST,
            "List everything in one module: workflows, runs (last 30 days, newest first), "
                + "functions, agents, devices (active), connectors, schedules (active), "
                + "notifications (open).",
            JsonSchemas.object(new LinkedHashMap<>(Map.of(
                "module", enumOf("Which module", modules),
                "status", JsonSchemas.string("Only items with this status, e.g. failed, running, "
                    + "draft, needs signing in again"))),
                List.of("module"))),
        new ToolSpec(GET,
            "Everything about one item: a workflow's steps, inputs, schedules and recent runs; a "
                + "run's status, error and the step it stopped at; a function's inputs and script; "
                + "a device's actions; a scheduled task's timing and last result; a question in "
                + "the inbox.",
            JsonSchemas.object(new LinkedHashMap<>(Map.of(
                "module", enumOf("Which module the item is in", modules),
                "id", JsonSchemas.string("The item's id, from search or list"))),
                List.of("module", "id"))));
  }

  @Override
  public List<ToolSpec> toolsFor(UUID agentId) {
    return orchestrator(agentId).isPresent() ? specs : List.of();
  }

  @Override
  public boolean supports(UUID agentId, String toolName) {
    return specs.stream().anyMatch(s -> s.name().equals(toolName)) && orchestrator(agentId).isPresent();
  }

  @Override
  public ToolResult invoke(UUID agentId, ToolCall call) {
    Optional<AgentEntity> jizza = orchestrator(agentId);
    if (jizza.isEmpty()) return ToolResult.error("Only Jizza can look through the workspace");
    UUID userId = jizza.get().getUserId();
    JsonNode args = call.args() == null ? Json.obj() : call.args();
    try {
      return switch (call.name()) {
        case OVERVIEW -> overview(userId);
        case SEARCH -> search(userId, args);
        case LIST -> list(userId, args);
        case GET -> get(userId, args);
        default -> ToolResult.error("Unknown tool " + call.name());
      };
    } catch (IllegalArgumentException e) {
      return ToolResult.error(e.getMessage());
    }
  }

  private ToolResult overview(UUID userId) {
    ObjectNode out = Json.obj();
    ArrayNode modules = out.putArray("modules");
    for (Workspace.Module m : Workspace.Module.values()) {
      List<Workspace.Item> items = workspace.list(userId, m);
      ObjectNode node = modules.addObject().put("module", m.key).put("what", m.about)
          .put("count", items.size()).put("how_to_act", m.howToAct);
      ArrayNode names = node.putArray("items");
      items.stream().limit(15).forEach(i -> names.add(i.toJson()));
    }
    out.put("note", "Memory and knowledge are searched with memory_recall and knowledge_search. "
        + "Use workspace_get on an item for its details.");
    return ToolResult.ok(out);
  }

  private ToolResult search(UUID userId, JsonNode args) {
    String query = args.path("query").asText("").strip();
    if (query.isEmpty()) return ToolResult.error("'query' must not be empty");
    List<Workspace.Module> modules = new ArrayList<>();
    for (JsonNode m : args.path("modules")) modules.add(module(m.asText()));
    List<Workspace.Item> hits = workspace.search(userId, query, modules, 10);
    ObjectNode out = Json.obj();
    ArrayNode found = out.putArray("found");
    hits.forEach(h -> found.add(h.toJson()));
    out.put("note", hits.isEmpty()
        ? "Nothing shares words with that. Try workspace_list on the module it would be in and "
            + "judge by meaning, or ask the user."
        : "Best matches first; judge by meaning which one the user means, and ask if it is unclear.");
    return ToolResult.ok(out);
  }

  private ToolResult list(UUID userId, JsonNode args) {
    Workspace.Module module = module(args.path("module").asText(""));
    String status = args.path("status").asText("").strip().toLowerCase(java.util.Locale.ROOT);
    List<Workspace.Item> items = workspace.list(userId, module).stream()
        .filter(i -> status.isEmpty() || (i.status() != null && i.status().contains(status)))
        .toList();
    ObjectNode out = Json.obj().put("module", module.key).put("count", items.size())
        .put("how_to_act", module.howToAct);
    ArrayNode list = out.putArray("items");
    items.stream().limit(MAX_LIST).forEach(i -> list.add(i.toJson()));
    if (items.size() > MAX_LIST) out.put("more", items.size() - MAX_LIST);
    return ToolResult.ok(out);
  }

  private ToolResult get(UUID userId, JsonNode args) {
    Workspace.Module module = module(args.path("module").asText(""));
    UUID id;
    try {
      id = UUID.fromString(args.path("id").asText(""));
    } catch (IllegalArgumentException e) {
      return ToolResult.error("'id' is not an id; take it from workspace_search or workspace_list");
    }
    return workspace.get(userId, module, id)
        .map(node -> {
          node.put("how_to_act", module.howToAct);
          return ToolResult.ok(node);
        })
        .orElseGet(() -> ToolResult.error("No " + module.key + " item with that id belongs to the user"));
  }

  private static Workspace.Module module(String key) {
    return Workspace.Module.of(key).orElseThrow(() -> new IllegalArgumentException(
        "Unknown module '" + key + "'; use one of workflows, runs, functions, agents, devices, "
            + "connectors, schedules, notifications"));
  }

  private Optional<AgentEntity> orchestrator(UUID agentId) {
    return agentId == null ? Optional.empty()
        : agents.findById(agentId).filter(AgentEntity::isOrchestrator);
  }

  private static ObjectNode enumOf(String description, List<String> values) {
    ObjectNode node = JsonSchemas.string(description);
    ArrayNode allowed = node.putArray("enum");
    values.forEach(allowed::add);
    return node;
  }

  private static ObjectNode stringEnumArray(String description, List<String> values) {
    ObjectNode node = Json.obj().put("type", "array").put("description", description);
    node.set("items", enumOf("A module", values));
    return node;
  }
}
