package com.jizza.platform.usecase.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.enums.ActionType;
import com.jizza.engine.api.enums.AutonomyRule;
import com.jizza.engine.api.enums.CapabilityType;
import com.jizza.engine.api.util.Json;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * One agent as a template, a proposal from Jizza or an organization template describes it, before
 * it is anyone's agent. A team is a list of these, linked by {@code reportsTo}.
 *
 * @param ref       a name for the agent inside its team, so others can say they report to it
 * @param reportsTo the {@code ref} of its manager in the same team; null for the team's lead, who
 *                  reports to whoever the team is added under
 * @param tools     tools to grant, by reference; only organization templates carry these
 * @param toolHints words describing the tools it needs, matched against what the user has
 * @param modes     its own mode per kind of action; kinds left out start from the safe profile
 * @param locks     kinds of action whose mode is a ceiling for everyone below it
 */
public record AgentBlueprint(String ref, String name, String description, String instructions,
                             JsonNode avatar, String modelTier, String reportsTo,
                             List<String> responsibilities, String handsOffTo,
                             List<String> memoryDomains, List<ToolRef> tools,
                             List<String> toolHints, Map<ActionType, AutonomyRule> modes,
                             Set<ActionType> locks) {

  public record ToolRef(CapabilityType type, UUID ref) {}

  public AgentBlueprint {
    responsibilities = responsibilities == null ? List.of() : List.copyOf(responsibilities);
    memoryDomains = memoryDomains == null ? List.of() : List.copyOf(memoryDomains);
    tools = tools == null ? List.of() : List.copyOf(tools);
    toolHints = toolHints == null ? List.of() : List.copyOf(toolHints);
    modes = modes == null || modes.isEmpty() ? Map.of() : Map.copyOf(modes);
    locks = locks == null || locks.isEmpty() ? Set.of() : Set.copyOf(locks);
  }

  /** Reads {@code {"agents":[...]}}, the shape templates and proposals are stored in. */
  public static List<AgentBlueprint> listFrom(JsonNode definition) {
    List<AgentBlueprint> out = new ArrayList<>();
    if (definition == null) return out;
    JsonNode agents = definition.has("agents") ? definition.path("agents") : definition;
    if (agents.isObject()) agents = Json.mapper().createArrayNode().add(agents);
    for (JsonNode node : agents) out.add(from(node));
    return out;
  }

  public static AgentBlueprint from(JsonNode n) {
    Map<ActionType, AutonomyRule> modes = new EnumMap<>(ActionType.class);
    n.path("modes").fields().forEachRemaining(e -> {
      ActionType type = actionType(e.getKey());
      AutonomyRule rule = rule(e.getValue().asText());
      if (type != null && rule != null) modes.put(type, rule);
    });
    Set<ActionType> locks = EnumSet.noneOf(ActionType.class);
    for (JsonNode lock : n.path("locks")) {
      ActionType type = actionType(lock.asText());
      if (type != null) locks.add(type);
    }
    List<ToolRef> tools = new ArrayList<>();
    for (JsonNode tool : n.path("tools")) {
      try {
        tools.add(new ToolRef(CapabilityType.valueOf(tool.path("type").asText()
            .toUpperCase(Locale.ROOT)), UUID.fromString(tool.path("ref").asText())));
      } catch (IllegalArgumentException ignored) {
        // A tool reference that no longer parses is left out; the review screen shows what's left.
      }
    }
    return new AgentBlueprint(text(n, "ref"), text(n, "name"), text(n, "description"),
        text(n, "instructions"), n.hasNonNull("avatar") ? n.get("avatar") : null,
        text(n, "modelTier"), text(n, "reportsTo"), strings(n.path("responsibilities")),
        text(n, "handsOffTo"), strings(n.path("memoryDomains")), tools,
        strings(n.path("toolHints")), modes, locks);
  }

  public ObjectNode toJson() {
    ObjectNode n = Json.obj();
    n.put("ref", ref);
    n.put("name", name);
    n.put("description", description);
    n.put("instructions", instructions);
    if (avatar != null) n.set("avatar", avatar);
    n.put("modelTier", modelTier == null ? "standard" : modelTier);
    n.put("reportsTo", reportsTo);
    n.set("responsibilities", array(responsibilities));
    n.put("handsOffTo", handsOffTo);
    n.set("memoryDomains", array(memoryDomains));
    ArrayNode toolArray = n.putArray("tools");
    for (ToolRef tool : tools) {
      toolArray.addObject().put("type", tool.type().name()).put("ref", tool.ref().toString());
    }
    n.set("toolHints", array(toolHints));
    ObjectNode modeNode = n.putObject("modes");
    modes.forEach((type, rule) -> modeNode.put(type.name(), rule.name()));
    ArrayNode lockArray = n.putArray("locks");
    locks.forEach(type -> lockArray.add(type.name()));
    return n;
  }

  public static ObjectNode teamJson(List<AgentBlueprint> agents) {
    ObjectNode definition = Json.obj();
    ArrayNode array = definition.putArray("agents");
    agents.forEach(agent -> array.add(agent.toJson()));
    return definition;
  }

  static ActionType actionType(String value) {
    if (value == null) return null;
    try {
      return ActionType.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  static AutonomyRule rule(String value) {
    if (value == null) return null;
    return switch (value.trim().toLowerCase(Locale.ROOT)) {
      case "allow", "auto" -> AutonomyRule.ALLOW;
      case "ask", "ask_me" -> AutonomyRule.ASK;
      case "jizza", "let_jizza_decide" -> AutonomyRule.JIZZA;
      case "deny", "never" -> AutonomyRule.DENY;
      default -> null;
    };
  }

  private static String text(JsonNode n, String field) {
    JsonNode v = n.path(field);
    return v.isMissingNode() || v.isNull() || v.asText().isBlank() ? null : v.asText();
  }

  private static List<String> strings(JsonNode array) {
    List<String> out = new ArrayList<>();
    for (JsonNode v : array) if (!v.asText().isBlank()) out.add(v.asText().trim());
    return out;
  }

  private static ArrayNode array(List<String> values) {
    ArrayNode array = Json.mapper().createArrayNode();
    values.forEach(array::add);
    return array;
  }
}
