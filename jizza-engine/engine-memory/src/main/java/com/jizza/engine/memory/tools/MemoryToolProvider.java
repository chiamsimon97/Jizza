package com.jizza.engine.memory.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.enums.DataClassification;
import com.jizza.engine.api.enums.MemoryCategory;
import com.jizza.engine.api.enums.MemorySource;
import com.jizza.engine.api.enums.SubjectKind;
import com.jizza.engine.api.memory.Actor;
import com.jizza.engine.api.memory.Memory;
import com.jizza.engine.api.memory.MemoryDraft;
import com.jizza.engine.api.memory.MemoryScope;
import com.jizza.engine.api.memory.MemoryStore;
import com.jizza.engine.api.memory.MemorySubject;
import com.jizza.engine.api.memory.MemoryWrite;
import com.jizza.engine.api.memory.SubjectDraft;
import com.jizza.engine.api.tool.JsonSchemas;
import com.jizza.engine.api.tool.ToolCall;
import com.jizza.engine.api.tool.ToolProvider;
import com.jizza.engine.api.tool.ToolResult;
import com.jizza.engine.api.tool.ToolSpec;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.memory.config.MemoryProperties;
import com.jizza.engine.memory.config.MemoryScopes;
import com.jizza.engine.persistence.entity.AgentEntity;
import com.jizza.engine.persistence.entity.ConnectionEntity;
import com.jizza.engine.persistence.repository.AgentRepository;
import com.jizza.engine.persistence.repository.ConnectionRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Memory tools every agent holds.
 *
 * <p>As with the knowledge tools, there is no user, agent or scope parameter anywhere in these
 * schemas: the user comes from the calling agent, and what it can read is that agent's scope. An
 * agent can record and retire facts but never delete one -- permanent deletion is the user's alone,
 * so a prompt injected through a connector response cannot erase what the user said.
 */
@Component
public class MemoryToolProvider implements ToolProvider {

  public static final String RECALL = "memory_recall";
  public static final String REMEMBER = "memory_remember";
  public static final String DESCRIBE = "memory_describe";
  public static final String RETIRE = "memory_retire";

  /** Tools whose arguments carry the user's personal detail and stay out of the audit log. */
  private static final Set<String> SENSITIVE = Set.of(REMEMBER, DESCRIBE);

  private final MemoryStore store;
  private final AgentRepository agents;
  private final ConnectionRepository connections;
  private final MemoryScopes scopes;
  private final MemoryProperties props;
  private final Map<String, ToolSpec> specs = new LinkedHashMap<>();

  public MemoryToolProvider(MemoryStore store, AgentRepository agents,
                            ConnectionRepository connections, MemoryScopes scopes,
                            MemoryProperties props) {
    this.store = store;
    this.agents = agents;
    this.connections = connections;
    this.scopes = scopes;
    this.props = props;

    specs.put(RECALL, new ToolSpec(RECALL,
        "Search the user's long-term memory for facts relevant to a query. Relevant facts are "
            + "already given to you at the start of each turn; use this to look further.",
        JsonSchemas.object(new LinkedHashMap<>(Map.of(
            "query", JsonSchemas.string("What to look for"),
            "k", JsonSchemas.integer("How many facts to return (default 8)"))),
            List.of("query"))));

    ObjectNode subjectRef = JsonSchemas.object(new LinkedHashMap<>(Map.of(
        "name", JsonSchemas.string("Name of the person or thing, e.g. 'Priya' or 'Priya's Zerodha account'"),
        "kind", enumSchema("What it is", SubjectKind.values()),
        "relation", JsonSchemas.string("How it relates to the user, e.g. 'wife', 'employer'"))),
        List.of("name"));
    ObjectNode about = Json.obj();
    about.put("type", "array");
    about.put("description", "Who or what the fact is about; leave out for facts about the user");
    about.set("items", subjectRef);

    specs.put(REMEMBER, new ToolSpec(REMEMBER,
        "Save a durable fact about the user, or about a person, account or plan in their life, so "
            + "every future conversation knows it. One fact per call, stated plainly. Only save what "
            + "will still matter later; never save credentials. Saving a new value for the same "
            + "'key' replaces the old one.",
        JsonSchemas.object(new LinkedHashMap<>(Map.of(
            "content", JsonSchemas.string("The fact, e.g. 'Priya invests Rs 20,000 a month in a Nifty 50 index fund'"),
            "category", enumSchema("What kind of fact", MemoryCategory.values()),
            "key", JsonSchemas.string("Optional attribute name this fact sets, e.g. 'risk_tolerance', 'monthly_sip'"),
            "about", about,
            "importance", number("0 to 1; 0.9+ for core facts every conversation should know"),
            "classification", enumSchema("Sensitivity", DataClassification.values()),
            "shared", JsonSchemas.bool("True to let every specialist see it, not just this domain"))),
            List.of("content", "category"))));

    ObjectNode linkedTo = JsonSchemas.object(new LinkedHashMap<>(Map.of(
        "name", JsonSchemas.string("The other subject's name"),
        "kind", enumSchema("What the other subject is", SubjectKind.values()),
        "relation", JsonSchemas.string("How this subject relates to it, e.g. 'held_by', 'plan_for', 'beneficiary_of'"))),
        List.of("name", "relation"));
    ObjectNode aliases = Json.obj();
    aliases.put("type", "array");
    aliases.put("description", "Other names the user uses for it");
    aliases.set("items", JsonSchemas.string(null));

    specs.put(DESCRIBE, new ToolSpec(DESCRIBE,
        "Record a person, account, asset, plan or organisation in the user's life, and optionally "
            + "how it links to another one or to one of the user's connectors. Facts saved about it "
            + "are grouped under it from then on.",
        JsonSchemas.object(new LinkedHashMap<>(Map.of(
            "name", JsonSchemas.string("Its name"),
            "kind", enumSchema("What it is", SubjectKind.values()),
            "relation", JsonSchemas.string("How it relates to the user, e.g. 'wife'"),
            "aliases", aliases,
            "connector", JsonSchemas.string("Name of the user's connector this account is reached through"),
            "linked_to", linkedTo)),
            List.of("name", "kind"))));

    specs.put(RETIRE, new ToolSpec(RETIRE,
        "Mark a remembered fact as no longer true (the user can still see and restore it). Use the "
            + "id shown next to the fact.",
        JsonSchemas.object(new LinkedHashMap<>(Map.of(
            "id", JsonSchemas.string("The fact's id"),
            "reason", JsonSchemas.string("Why it is no longer true"))),
            List.of("id"))));
  }

  @Override
  public List<ToolSpec> toolsFor(UUID agentId) {
    return props.isEnabled() ? List.copyOf(specs.values()) : List.of();
  }

  @Override
  public boolean supports(UUID agentId, String toolName) {
    return props.isEnabled() && specs.containsKey(toolName);
  }

  @Override
  public boolean redactArguments(String toolName) {
    return SENSITIVE.contains(toolName);
  }

  @Override
  public ToolResult invoke(UUID agentId, ToolCall call) {
    AgentEntity agent = agents.findById(agentId).orElse(null);
    if (agent == null) return ToolResult.error("Unknown agent");
    JsonNode args = call.args() == null ? Json.obj() : call.args();
    try {
      return switch (call.name()) {
        case RECALL -> recall(agent, args);
        case REMEMBER -> remember(agent, args);
        case DESCRIBE -> describe(agent, args);
        case RETIRE -> retire(agent, args);
        default -> ToolResult.error("Unknown memory tool " + call.name());
      };
    } catch (IllegalArgumentException e) {
      return ToolResult.error(e.getMessage());
    }
  }

  private ToolResult recall(AgentEntity agent, JsonNode args) {
    List<Memory> found = store.search(agent.getUserId(), args.path("query").asText(""),
        scopes.forAgent(agent), args.path("k").asInt(8));
    Map<UUID, String> names = subjectNames(agent);
    ArrayNode out = Json.mapper().createArrayNode();
    for (Memory m : found) {
      ObjectNode node = out.addObject();
      node.put("id", m.id().toString());
      node.put("fact", m.content());
      node.put("category", m.category().name().toLowerCase(Locale.ROOT));
      ArrayNode aboutNames = node.putArray("about");
      m.subjectIds().forEach(id -> aboutNames.add(names.getOrDefault(id, "?")));
      node.put("confirmed", m.confirmed());
      node.put("updated", String.valueOf(m.updatedAt()));
      if (m.score() != null) node.put("relevance", Math.round(m.score() * 1000) / 1000.0);
    }
    return ToolResult.ok(out);
  }

  private ToolResult remember(AgentEntity agent, JsonNode args) {
    MemoryCategory category = parse(MemoryCategory.class, args.path("category").asText(null), "category");
    List<UUID> about = new ArrayList<>();
    for (JsonNode ref : args.path("about")) {
      about.add(subjectFor(agent, ref.path("name").asText(), kindOf(ref.path("kind")),
          textOrNull(ref.path("relation")), category).id());
    }
    MemoryDraft draft = new MemoryDraft(category, args.path("content").asText(),
        textOrNull(args.path("key")), null,
        args.hasNonNull("classification")
            ? parse(DataClassification.class, args.path("classification").asText(), "classification") : null,
        props.getWrite().getAgentConfidence(),
        args.hasNonNull("importance") ? args.path("importance").asDouble() : null,
        MemorySource.AGENT, "agent:" + agent.getKey(), false, about,
        args.path("shared").asBoolean(false) ? List.of() : null, null);

    MemoryWrite write = store.remember(agent.getUserId(), draft, Actor.agent(agent.getId()));
    ObjectNode out = Json.obj();
    out.put("id", write.memory().id().toString());
    out.put("outcome", write.outcome().name().toLowerCase(Locale.ROOT));
    switch (write.outcome()) {
      case DUPLICATE -> out.put("note", "Already known; the existing fact was reinforced.");
      case SUPERSEDED_PREVIOUS -> out.put("note", "Replaced the previous value for this key.");
      case PENDING_CONFLICT -> out.put("note", "This conflicts with something the user confirmed, so "
          + "it is waiting for the user to confirm it. Mention the discrepancy to the user.");
      default -> { }
    }
    return ToolResult.ok(out);
  }

  private ToolResult describe(AgentEntity agent, JsonNode args) {
    SubjectKind kind = kindOf(args.path("kind"));
    UUID connectionId = null;
    if (args.hasNonNull("connector")) {
      connectionId = connectionNamed(agent.getUserId(), args.path("connector").asText())
          .map(ConnectionEntity::getId)
          .orElseThrow(() -> new IllegalArgumentException("No connector named '"
              + args.path("connector").asText() + "'. The user's connectors: "
              + String.join(", ", connections.findByUserIdOrderByServiceNameAsc(agent.getUserId())
                  .stream().map(ConnectionEntity::getServiceName).toList())));
    }
    List<String> aliases = new ArrayList<>();
    args.path("aliases").forEach(a -> aliases.add(a.asText()));

    MemorySubject subject = subjectFor(agent, args.path("name").asText(), kind,
        textOrNull(args.path("relation")), null);
    subject = store.updateSubject(agent.getUserId(), subject.id(), new SubjectDraft(null, null,
        textOrNull(args.path("relation")), aliases.isEmpty() ? null : aliases, null, connectionId, null),
        Actor.agent(agent.getId()));

    ObjectNode out = Json.obj();
    out.put("id", subject.id().toString());
    out.put("name", subject.name());
    JsonNode link = args.path("linked_to");
    if (link.isObject()) {
      MemorySubject other = subjectFor(agent, link.path("name").asText(), kindOf(link.path("kind")), null, null);
      store.relate(agent.getUserId(), subject.id(), other.id(), link.path("relation").asText(),
          Actor.agent(agent.getId()));
      out.put("linked_to", other.name());
    }
    return ToolResult.ok(out);
  }

  private ToolResult retire(AgentEntity agent, JsonNode args) {
    UUID id;
    try {
      id = UUID.fromString(args.path("id").asText());
    } catch (IllegalArgumentException e) {
      return ToolResult.error("'id' must be a memory id");
    }
    MemoryScope scope = scopes.forAgent(agent);
    Optional<Memory> memory = store.get(agent.getUserId(), id).filter(m -> scope.sees(m.domains()));
    if (memory.isEmpty()) return ToolResult.error("No memory with id " + id + " that you can see");
    store.retire(agent.getUserId(), id, textOrNull(args.path("reason")), Actor.agent(agent.getId()));
    return ToolResult.ok(Json.obj().put("id", id.toString()).put("status", "retired"));
  }

  /**
   * An existing subject this agent can see, matched by name, alias or relation; otherwise a new one.
   * Accounts, assets and plans an agent creates are kept to its own domain; people and
   * organisations are shared so every agent knows who they are.
   */
  private MemorySubject subjectFor(AgentEntity agent, String name, SubjectKind kind, String relation,
                                   MemoryCategory category) {
    if (name == null || name.isBlank()) throw new IllegalArgumentException("Every subject needs a name");
    String wanted = name.strip();
    MemoryScope scope = scopes.forAgent(agent);
    for (MemorySubject s : store.subjects(agent.getUserId(), scope)) {
      boolean sameName = s.name().equalsIgnoreCase(wanted)
          || s.aliases().stream().anyMatch(a -> a.equalsIgnoreCase(wanted))
          || (s.relation() != null && s.relation().equalsIgnoreCase(wanted));
      if (sameName && (kind == null || kind == s.kind())) return s;
    }
    SubjectKind resolvedKind = kind == null ? SubjectKind.OTHER : kind;
    List<String> domains = List.of();
    boolean domainBound = resolvedKind == SubjectKind.ACCOUNT || resolvedKind == SubjectKind.ASSET
        || resolvedKind == SubjectKind.PLAN;
    if (domainBound && !agent.isOrchestrator() && agent.getDomain() != null) {
      domains = List.of(agent.getDomain());
    } else if (domainBound && category != null) {
      domains = scopes.defaultDomains(category);
    }
    return store.upsertSubject(agent.getUserId(),
        new SubjectDraft(resolvedKind, wanted, relation, null, null, null, domains),
        Actor.agent(agent.getId()));
  }

  private Optional<ConnectionEntity> connectionNamed(UUID userId, String name) {
    String wanted = name.strip();
    return connections.findByUserIdOrderByServiceNameAsc(userId).stream()
        .filter(c -> c.getServiceName().equalsIgnoreCase(wanted)
            || (c.getSlug() != null && c.getSlug().equalsIgnoreCase(wanted)))
        .findFirst();
  }

  private Map<UUID, String> subjectNames(AgentEntity agent) {
    Map<UUID, String> names = new LinkedHashMap<>();
    store.subjects(agent.getUserId(), scopes.forAgent(agent)).forEach(s -> names.put(s.id(), s.name()));
    return names;
  }

  private static SubjectKind kindOf(JsonNode node) {
    return node.isTextual() && !node.asText().isBlank()
        ? parse(SubjectKind.class, node.asText(), "kind") : null;
  }

  private static <E extends Enum<E>> E parse(Class<E> type, String value, String field) {
    if (value == null) throw new IllegalArgumentException("'" + field + "' is required");
    try {
      return Enum.valueOf(type, value.strip().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("'" + value + "' is not a valid " + field);
    }
  }

  private static String textOrNull(JsonNode node) {
    return node.isTextual() && !node.asText().isBlank() ? node.asText() : null;
  }

  private static ObjectNode number(String description) {
    ObjectNode n = Json.obj();
    n.put("type", "number");
    n.put("description", description);
    return n;
  }

  private static ObjectNode enumSchema(String description, Enum<?>[] values) {
    ObjectNode node = JsonSchemas.string(description);
    ArrayNode allowed = node.putArray("enum");
    for (Enum<?> v : values) allowed.add(v.name().toLowerCase(Locale.ROOT));
    return node;
  }
}
