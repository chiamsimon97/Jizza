package com.jizza.knowledge.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.common.enums.DataClassification;
import com.jizza.common.knowledge.KnowledgeEntry;
import com.jizza.common.knowledge.KnowledgeStore;
import com.jizza.common.tool.JsonSchemas;
import com.jizza.common.tool.ToolCall;
import com.jizza.common.tool.ToolProvider;
import com.jizza.common.tool.ToolResult;
import com.jizza.common.tool.ToolSpec;
import com.jizza.common.util.Json;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The knowledge tools every specialist gets (R17).
 *
 * <p>Note what these schemas do <em>not</em> contain: there is no agent, namespace or owner
 * parameter. The namespace comes from the {@code agentId} the Capability Registry passes in, so a
 * model cannot reach another agent's knowledge even by asking for it in the arguments.
 */
@Component
public class KnowledgeToolProvider implements ToolProvider {

  public static final String SEARCH = "knowledge_search";
  public static final String GET = "knowledge_get";
  public static final String UPSERT = "knowledge_upsert";

  private final KnowledgeStore store;
  private final Map<String, ToolSpec> specs = new LinkedHashMap<>();

  public KnowledgeToolProvider(KnowledgeStore store) {
    this.store = store;

    specs.put(SEARCH, new ToolSpec(SEARCH,
        "Search your own knowledge for entries relevant to a query. You can only see your own "
            + "knowledge; other agents' knowledge is not reachable.",
        JsonSchemas.object(new LinkedHashMap<>(Map.of(
            "query", JsonSchemas.string("What to look for"),
            "k", JsonSchemas.integer("How many entries to return (default 5)"))),
            List.of("query"))));

    specs.put(GET, new ToolSpec(GET, "Fetch one of your knowledge entries by id.",
        JsonSchemas.object(new LinkedHashMap<>(Map.of(
            "id", JsonSchemas.string("Entry id returned by knowledge_search"))),
            List.of("id"))));

    specs.put(UPSERT, new ToolSpec(UPSERT,
        "Record or update something in your own knowledge. Tag health, financial or personal "
            + "details with the matching classification.",
        JsonSchemas.object(new LinkedHashMap<>(Map.of(
            "id", JsonSchemas.string("Entry id to update; omit to create a new entry"),
            "title", JsonSchemas.string("Short title"),
            "content", JsonSchemas.string("The knowledge itself, in prose"),
            "data", JsonSchemas.objectSchema("Optional structured fields"),
            "classification", classificationSchema())),
            List.of("title"))));
  }

  private static ObjectNode classificationSchema() {
    ObjectNode node = JsonSchemas.string("Data classification for this entry");
    ArrayNode allowed = node.putArray("enum");
    for (DataClassification c : DataClassification.values()) {
      allowed.add(c.name().toLowerCase(Locale.ROOT));
    }
    return node;
  }

  @Override
  public List<ToolSpec> toolsFor(UUID agentId) {
    return List.copyOf(specs.values());
  }

  @Override
  public boolean supports(UUID agentId, String toolName) {
    return specs.containsKey(toolName);
  }

  @Override
  public ToolResult invoke(UUID agentId, ToolCall call) {
    JsonNode args = call.args() == null ? Json.obj() : call.args();
    return switch (call.name()) {
      case SEARCH -> search(agentId, args);
      case GET -> get(agentId, args);
      case UPSERT -> upsert(agentId, args);
      default -> ToolResult.error("Unknown knowledge tool " + call.name());
    };
  }

  private ToolResult search(UUID agentId, JsonNode args) {
    List<KnowledgeEntry> results = store.search(agentId, args.path("query").asText(""),
        args.path("k").asInt(5));
    ArrayNode out = Json.mapper().createArrayNode();
    for (KnowledgeEntry entry : results) out.add(summarise(entry));
    return ToolResult.ok(out);
  }

  private ToolResult get(UUID agentId, JsonNode args) {
    UUID id;
    try {
      id = UUID.fromString(args.path("id").asText());
    } catch (IllegalArgumentException e) {
      return ToolResult.error("'id' must be a knowledge entry id");
    }
    return store.get(agentId, id)
        .map(entry -> ToolResult.ok(full(entry)))
        .orElseGet(() -> ToolResult.error("No entry with id " + id + " in your knowledge"));
  }

  private ToolResult upsert(UUID agentId, JsonNode args) {
    UUID id = args.hasNonNull("id") && !args.path("id").asText().isBlank()
        ? UUID.fromString(args.path("id").asText()) : null;
    DataClassification classification = args.hasNonNull("classification")
        ? DataClassification.valueOf(args.path("classification").asText().toUpperCase(Locale.ROOT))
        : DataClassification.NONE;

    KnowledgeEntry entry = new KnowledgeEntry(id, null, agentId,
        args.path("title").asText(), args.path("content").asText(null),
        args.hasNonNull("data") ? args.get("data") : null, classification, null, null, null);

    KnowledgeEntry saved = store.upsert(agentId, entry);
    return ToolResult.ok(Json.obj()
        .put("id", saved.id().toString())
        .put("title", saved.title())
        .put("classification", String.valueOf(saved.classification()).toLowerCase(Locale.ROOT)));
  }

  private ObjectNode summarise(KnowledgeEntry entry) {
    ObjectNode node = Json.obj();
    node.put("id", entry.id().toString());
    node.put("title", entry.title());
    node.put("excerpt", excerpt(entry.content()));
    if (entry.score() != null) node.put("relevance", Math.round(entry.score() * 1000) / 1000.0);
    return node;
  }

  private ObjectNode full(KnowledgeEntry entry) {
    ObjectNode node = Json.obj();
    node.put("id", entry.id().toString());
    node.put("title", entry.title());
    node.put("content", entry.content());
    node.put("classification", String.valueOf(entry.classification()).toLowerCase(Locale.ROOT));
    if (entry.data() != null) node.set("data", entry.data());
    return node;
  }

  private String excerpt(String content) {
    if (content == null) return null;
    return content.length() <= 240 ? content : content.substring(0, 240) + "...";
  }
}
