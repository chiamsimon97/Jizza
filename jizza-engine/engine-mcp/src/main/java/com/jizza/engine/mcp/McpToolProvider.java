package com.jizza.engine.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
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
 *
 * <p>Servers come from configuration and, when {@link StoredMcpServers} is present, from the ones
 * the agent's owner added in the app. A configured server wins a name both use. An added server is
 * offered with the tools the user reviewed and left on, so a turn never waits on the server to list
 * them; its session is opened on the first call and kept, which is what keeps a server that signs
 * in inside its own session (Zerodha's Kite) signed in between turns.
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
  /** Tool-name prefix to whether its arguments are kept out of the audit log, for added servers. */
  private final Map<String, Boolean> redactedPrefixes = new ConcurrentHashMap<>();
  private StoredMcpServers stored;

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

  /** The servers the user added in the app; without it only configured servers are offered. */
  public void setStoredServers(StoredMcpServers stored) {
    this.stored = stored;
  }

  /** One tool as the model sees it, and where it really lives. */
  record Exposed(String name, String server, McpProperties.Server config, McpSchema.Tool tool) {}

  @Override
  public List<ToolSpec> toolsFor(UUID agentId) {
    List<ToolSpec> specs = new ArrayList<>();
    for (Exposed exposed : exposedTo(agentId)) {
      McpProperties.Server server = exposed.config();
      ActionType type = server.getActionTypes().get(exposed.tool().name());
      boolean sideEffecting = type == null || type.sideEffecting();
      // Deferrable: when the outside tools add up to a lot, the model searches for them.
      specs.add(new ToolSpec(exposed.name(), describe(exposed), schema(exposed.tool()),
          sideEffecting, true));
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
    Connection connection = connection(exposed.get().server(), exposed.get().config());
    try {
      Map<String, Object> arguments = call.args() == null || call.args().isNull()
          ? Map.of() : Json.mapper().convertValue(call.args(),
              new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
      return connection.signedInBy(exposed.get().tool().name(),
          toResult(connection.session().callTool(exposed.get().tool().name(), arguments)));
    } catch (RuntimeException e) {
      log.warn("MCP tool {} failed", call.name(), e);
      // A broken session is opened afresh on the next turn rather than reused.
      connection.drop();
      return ToolResult.error("The MCP server could not run that: " + Redaction.scrub(e.getMessage()));
    }
  }

  @Override
  public boolean redactArguments(String toolName) {
    Optional<McpProperties.Server> configured = serverOf(toolName);
    if (configured.isPresent()) return configured.get().isRedactArguments();
    if (toolName == null) return false;
    return redactedPrefixes.entrySet().stream()
        .anyMatch(e -> toolName.startsWith(e.getKey()) && e.getValue());
  }

  /** The account that owns an added server, so the registry checks the owner's access there. */
  @Override
  public Optional<UUID> workspaceOf(UUID agentId, String toolName) {
    if (toolName == null || !toolName.startsWith(PREFIX)) return Optional.empty();
    return serversFor(ownerOf(agentId)).entrySet().stream()
        .filter(e -> toolName.startsWith(PREFIX + slug(e.getKey()) + "__"))
        .map(e -> e.getValue().getWorkspaceId()).filter(java.util.Objects::nonNull).findFirst();
  }

  /** What the owner said the tool does; empty for a tool they did not classify. */
  @Override
  public Optional<ActionType> actionTypeOf(UUID agentId, String toolRef) {
    if (toolRef == null || !toolRef.startsWith(PREFIX)) return Optional.empty();
    for (var entry : serversFor(ownerOf(agentId)).entrySet()) {
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

  // ── Direct use, by the service that manages added servers ────────────────────

  /** Asks the server for its tools now, through the kept session. Throws when it cannot. */
  public List<McpSchema.Tool> discover(String name, McpProperties.Server server) {
    return connection(name, server).listNow();
  }

  /** Who the server said it is when the session opened; null before it has been reached. */
  public McpSchema.Implementation serverInfo(String name, McpProperties.Server server) {
    return connection(name, server).info();
  }

  /** Calls a tool by the server's own name. The caller has already judged the call. */
  public ToolResult callTool(String name, McpProperties.Server server, String tool,
                             Map<String, Object> arguments) {
    Connection connection = connection(name, server);
    try {
      return connection.signedInBy(tool, toResult(connection.session().callTool(tool, arguments)));
    } catch (RuntimeException e) {
      log.warn("MCP tool {} on '{}' failed", tool, name, e);
      connection.drop();
      return ToolResult.error("The MCP server could not run that: " + Redaction.scrub(e.getMessage()));
    }
  }

  /** A tool by which a server signs the person in inside its own session (Zerodha Kite's login). */
  static final java.util.regex.Pattern SIGN_IN_TOOL =
      java.util.regex.Pattern.compile("(log_?in|sign_?in|authenticate|authori[sz]e)", java.util.regex.Pattern.CASE_INSENSITIVE);

  /** Whether {@code tool} is one by which the server signs the person in inside its session. */
  public static boolean signInTool(String tool) {
    return tool != null && SIGN_IN_TOOL.matcher(tool).matches();
  }

  /**
   * When the server's sign-in tool last answered in the session open now: null when it has not
   * since the session opened. A server that signs in inside its session (Kite) forgets the sign-in
   * when the session goes, after a restart of Jizza included, so this says whether to sign in again.
   */
  public Instant sessionSignInAt(UUID serverId) {
    Connection connection = connections.get(keyOf(serverId, null));
    return connection == null ? null : connection.signInAt();
  }

  /** Closes an added server's session, after an edit, a sign-out or a delete. */
  public void forget(UUID serverId) {
    Connection connection = connections.remove(keyOf(serverId, null));
    if (connection != null) connection.drop();
  }

  // ── Which tools an agent sees ─────────────────────────────────────────────────

  private UUID ownerOf(UUID agentId) {
    return agentId == null ? null : agents.findById(agentId).map(AgentEntity::getUserId).orElse(null);
  }

  /** The configured servers, then the ones the owner added, by the name their tools carry. */
  Map<String, McpProperties.Server> serversFor(UUID userId) {
    Map<String, McpProperties.Server> all = new java.util.LinkedHashMap<>(props.getServers());
    if (stored == null || userId == null) return all;
    try {
      stored.serversFor(userId).forEach((name, server) -> {
        if (all.putIfAbsent(name, server) == null) {
          redactedPrefixes.put(PREFIX + slug(name) + "__", server.isRedactArguments());
        }
      });
    } catch (RuntimeException e) {
      log.warn("Could not read the MCP servers added in the app; offering configured ones only", e);
    }
    return all;
  }

  private List<Exposed> exposedTo(UUID agentId) {
    AgentEntity agent = agentId == null ? null : agents.findById(agentId).orElse(null);
    if (agent == null || agent.getKey() == null) return List.of();
    String key = agent.getKey();
    List<Exposed> exposed = new ArrayList<>();
    serversFor(agent.getUserId()).forEach((name, server) -> {
      if (!server.offeredTo(key)) return;
      List<McpSchema.Tool> tools = server.getKnownTools() != null ? server.getKnownTools()
          : connection(name, server).tools();
      for (McpSchema.Tool tool : tools) {
        if (server.offers(tool.name())) {
          exposed.add(new Exposed(toolName(name, tool.name()), name, server, tool));
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

  private static String keyOf(UUID serverId, String name) {
    return serverId != null ? "app:" + serverId : "config:" + name;
  }

  /** The kept connection, opened afresh when what it depends on (url, headers) has changed. */
  private Connection connection(String name, McpProperties.Server server) {
    String fingerprint = server.fingerprint();
    return connections.compute(keyOf(server.getId(), name), (k, existing) -> {
      if (existing != null && existing.fingerprint.equals(fingerprint)) return existing;
      if (existing != null) existing.drop();
      return new Connection(name, server, fingerprint);
    });
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
    return ToolResult.ok(asJson(text.toString()));
  }

  /**
   * Many servers (Kite among them) return JSON as text. It is read as JSON, so a workflow step can
   * map its fields and the page can show it as data rather than as one long string.
   */
  static JsonNode asJson(String text) {
    String trimmed = text.strip();
    if (trimmed.startsWith("{") && trimmed.endsWith("}")
        || trimmed.startsWith("[") && trimmed.endsWith("]")) {
      try {
        return Json.mapper().readTree(trimmed);
      } catch (Exception e) {
        // Text that only looks like JSON stays text.
      }
    }
    return TextNode.valueOf(text);
  }

  // ── One server's connection ───────────────────────────────────────────────────

  /** Opened on first use, its tool list reused for a while, reopened after a failure. */
  private final class Connection {
    private final String name;
    private final McpProperties.Server server;
    private final String fingerprint;
    private McpSession session;
    private McpSchema.Implementation info;
    private List<McpSchema.Tool> tools = List.of();
    private Instant listedAt;
    private Instant unreachableUntil;
    /** When the server's sign-in tool answered in this session; null since the session opened. */
    private Instant signInAt;

    Connection(String name, McpProperties.Server server, String fingerprint) {
      this.name = name;
      this.server = server;
      this.fingerprint = fingerprint;
    }

    /** Lists now, ignoring the cache and the back-off; a failure is the caller's to report. */
    synchronized List<McpSchema.Tool> listNow() {
      try {
        tools = List.copyOf(session().listTools());
        listedAt = clock.instant();
        unreachableUntil = null;
        return tools;
      } catch (RuntimeException e) {
        closeQuietly();
        listedAt = null;
        throw e;
      }
    }

    synchronized McpSchema.Implementation info() { return info; }

    synchronized Instant signInAt() { return session == null ? null : signInAt; }

    /** Notes a sign-in tool that answered without an error, and hands its result on. */
    synchronized ToolResult signedInBy(String tool, ToolResult result) {
      if (result.ok() && signInTool(tool)) signInAt = clock.instant();
      return result;
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
      if (session == null) {
        session = sessions.open(name, server);
        info = session.serverInfo();
        signInAt = null;
      }
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
