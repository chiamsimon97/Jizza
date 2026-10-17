package com.jizza.engine.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.autonomy.ActionTypeSource;
import com.jizza.engine.api.enums.ActionType;
import com.jizza.engine.api.tool.JsonSchemas;
import com.jizza.engine.api.tool.ToolCall;
import com.jizza.engine.api.tool.ToolProvider;
import com.jizza.engine.api.tool.ToolResult;
import com.jizza.engine.api.tool.ToolSpec;
import com.jizza.engine.api.util.Hashing;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.util.Redaction;
import com.jizza.engine.persistence.entity.AgentEntity;
import com.jizza.engine.persistence.repository.AgentRepository;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;

/**
 * The tools of the configured MCP servers, offered to the agents the owner named for each server.
 *
 * <p>This is only a provider: the Capability Registry still executes every call, so an MCP tool
 * passes the same checks as any other, in the same order (agent enabled, kill switch, schema,
 * account access, guardrails, audit). What a tool does, for the guardrails, comes from the owner's
 * configuration ({@link ActionTypeSource}); a tool the owner has not classified asks every time.
 *
 * <p>A model sees {@code mcp_<server>__<tool>}, so an MCP tool can never be mistaken for a
 * built-in one or for a connector tool. A server that cannot be reached is left out of the turn and
 * tried again a minute later; it never costs the agent its answer.
 */
public class McpToolProvider implements ToolProvider, ActionTypeSource, DisposableBean {

  private static final Logger log = LoggerFactory.getLogger(McpToolProvider.class);

  static final String PREFIX = "mcp_";
  /** The Messages API limit on a tool name. */
  private static final int MAX_NAME = 64;
  /** Tool descriptions are the server's words placed in the model's context; kept short. */
  private static final int MAX_DESCRIPTION = 1024;
  private static final java.time.Duration RETRY_UNREACHABLE = java.time.Duration.ofMinutes(1);

  private final McpProperties props;
  private final McpSession.Factory sessions;
  private final AgentRepository agents;
  private final Clock clock;
  private final Map<String, Connection> connections = new ConcurrentHashMap<>();

  public McpToolProvider(McpProperties props, McpSession.Factory sessions, AgentRepository agents) {
    this(props, sessions, agents, Clock.systemUTC());
  }

  McpToolProvider(McpProperties props, McpSession.Factory sessions, AgentRepository agents,
                  Clock clock) {
    this.props = props;
    this.sessions = sessions;
    this.agents = agents;
    this.clock = clock;
  }

  /** One tool as the model sees it, and where it really lives. */
  record Exposed(String name, String server, McpSchema.Tool tool) {}

  @Override
  public List<ToolSpec> toolsFor(UUID agentId) {
    List<ToolSpec> specs = new ArrayList<>();
    for (Exposed exposed : exposedTo(agentId)) {
      McpProperties.Server server = props.getServers().get(exposed.server());
      ActionType type = server.getActionTypes().get(exposed.tool().name());
      boolean sideEffecting = type == null || type.sideEffecting();
      specs.add(new ToolSpec(exposed.name(), describe(exposed), schema(exposed.tool()),
          sideEffecting));
    }
    return specs;
  }

  @Override
  public boolean supports(UUID agentId, String toolName) {
    return toolName.startsWith(PREFIX) && find(agentId, toolName).isPresent();
  }

  @Override
  public ToolResult invoke(UUID agentId, ToolCall call) {
    Optional<Exposed> exposed = find(agentId, call.name());
    if (exposed.isEmpty()) {
      return ToolResult.error("Tool '" + call.name() + "' is not available to this agent");
    }
    Connection connection = connections.get(exposed.get().server());
    if (connection == null) return ToolResult.error("That MCP server is not connected");
    try {
      Map<String, Object> arguments = call.args() == null || call.args().isNull()
          ? Map.of() : Json.mapper().convertValue(call.args(),
              new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
      return toResult(connection.session().callTool(exposed.get().tool().name(), arguments));
    } catch (RuntimeException e) {
      log.warn("MCP tool {} failed", call.name(), e);
      // A broken session is opened afresh on the next turn rather than reused.
      connection.drop();
      return ToolResult.error("The MCP server could not run that: " + Redaction.scrub(e.getMessage()));
    }
  }

  @Override
  public boolean redactArguments(String toolName) {
    return serverOf(toolName).map(McpProperties.Server::isRedactArguments).orElse(false);
  }

  /** What the owner said the tool does; empty for a tool they did not classify. */
  @Override
  public Optional<ActionType> actionTypeOf(UUID agentId, String toolRef) {
    if (toolRef == null || !toolRef.startsWith(PREFIX)) return Optional.empty();
    for (var entry : props.getServers().entrySet()) {
      String prefix = PREFIX + slug(entry.getKey()) + "__";
      if (!toolRef.startsWith(prefix)) continue;
      for (var type : entry.getValue().getActionTypes().entrySet()) {
        if (toolName(entry.getKey(), type.getKey()).equals(toolRef)) {
          return Optional.ofNullable(type.getValue());
        }
      }
    }
    return Optional.empty();
  }

  @Override
  public void destroy() {
    connections.values().forEach(Connection::drop);
    connections.clear();
  }

  // ── Which tools an agent sees ─────────────────────────────────────────────────

  private List<Exposed> exposedTo(UUID agentId) {
    String key = agents.findById(agentId).map(AgentEntity::getKey).orElse(null);
    if (key == null) return List.of();
    List<Exposed> exposed = new ArrayList<>();
    props.getServers().forEach((name, server) -> {
      if (!server.offeredTo(key)) return;
      for (McpSchema.Tool tool : connection(name, server).tools()) {
        if (server.offers(tool.name())) {
          exposed.add(new Exposed(toolName(name, tool.name()), name, tool));
        }
      }
    });
    return exposed;
  }

  private Optional<Exposed> find(UUID agentId, String toolName) {
    return exposedTo(agentId).stream().filter(e -> e.name().equals(toolName)).findFirst();
  }

  private Optional<McpProperties.Server> serverOf(String toolName) {
    if (toolName == null || !toolName.startsWith(PREFIX)) return Optional.empty();
    return props.getServers().entrySet().stream()
        .filter(e -> toolName.startsWith(PREFIX + slug(e.getKey()) + "__"))
        .map(Map.Entry::getValue).findFirst();
  }

  private Connection connection(String name, McpProperties.Server server) {
    return connections.computeIfAbsent(name, n -> new Connection(n, server));
  }

  // ── Naming and translation ────────────────────────────────────────────────────

  /** {@code mcp_<server>__<tool>}, within the provider's name rules and length. */
  static String toolName(String server, String tool) {
    String name = PREFIX + slug(server) + "__" + slug(tool);
    if (name.length() <= MAX_NAME) return name;
    String hash = Hashing.sha256Hex(server + "/" + tool).substring(0, 8);
    return name.substring(0, MAX_NAME - 9) + "_" + hash;
  }

  static String slug(String s) {
    return s.replaceAll("[^A-Za-z0-9_-]", "_");
  }

  private static String describe(Exposed exposed) {
    String own = exposed.tool().description() == null ? "" : exposed.tool().description().strip();
    if (own.length() > MAX_DESCRIPTION) own = own.substring(0, MAX_DESCRIPTION) + "…";
    return Redaction.scrub("[" + exposed.server() + " via MCP] " + own);
  }

  private static JsonNode schema(McpSchema.Tool tool) {
    if (tool.inputSchema() == null || tool.inputSchema().isEmpty()) return JsonSchemas.emptyObject();
    JsonNode schema = Json.toNode(tool.inputSchema());
    return schema.isObject() && schema.has("type") ? schema : JsonSchemas.emptyObject();
  }

  /**
   * What the server returned, as a tool result. Structured content is passed on as data; text is
   * joined; anything else (an image, a resource) is named rather than inlined.
   */
  static ToolResult toResult(McpSchema.CallToolResult result) {
    StringBuilder text = new StringBuilder();
    if (result.content() != null) {
      for (McpSchema.Content content : result.content()) {
        if (!text.isEmpty()) text.append('\n');
        if (content instanceof McpSchema.TextContent t) text.append(t.text());
        else text.append('[').append(content.type()).append(" content not shown]");
      }
    }
    if (Boolean.TRUE.equals(result.isError())) {
      return ToolResult.error(text.isEmpty() ? "The MCP tool reported an error" : text.toString());
    }
    if (result.structuredContent() != null) return ToolResult.ok(Json.toNode(result.structuredContent()));
    return ToolResult.ok(text.toString());
  }

  // ── One server's connection ───────────────────────────────────────────────────

  /** Opened on first use, its tool list reused for a while, reopened after a failure. */
  private final class Connection {
    private final String name;
    private final McpProperties.Server server;
    private McpSession session;
    private List<McpSchema.Tool> tools = List.of();
    private Instant listedAt;
    private Instant unreachableUntil;

    Connection(String name, McpProperties.Server server) {
      this.name = name;
      this.server = server;
    }

    synchronized List<McpSchema.Tool> tools() {
      Instant now = clock.instant();
      if (unreachableUntil != null && now.isBefore(unreachableUntil)) return List.of();
      if (listedAt != null && now.isBefore(listedAt.plus(server.getToolsCacheTtl()))) return tools;
      try {
        tools = List.copyOf(session().listTools());
        listedAt = now;
        unreachableUntil = null;
      } catch (RuntimeException e) {
        log.warn("MCP server '{}' is unreachable; leaving its tools out for now: {}", name,
            e.getMessage());
        closeQuietly();
        tools = List.of();
        listedAt = null;
        unreachableUntil = now.plus(RETRY_UNREACHABLE);
      }
      return tools;
    }

    synchronized McpSession session() {
      if (session == null) session = sessions.open(name, server);
      return session;
    }

    synchronized void drop() {
      closeQuietly();
      listedAt = null;
    }

    private void closeQuietly() {
      if (session == null) return;
      try {
        session.close();
      } catch (RuntimeException e) {
        log.debug("Closing MCP server '{}' failed", name, e);
      }
      session = null;
    }
  }
}
