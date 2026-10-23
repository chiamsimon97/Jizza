package com.jizza.engine.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.crypto.EncryptionService;
import com.jizza.engine.api.enums.ActionType;
import com.jizza.engine.api.error.AuthException;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.mcp.McpAuthType;
import com.jizza.engine.api.mcp.McpServerDto;
import com.jizza.engine.api.mcp.McpServerSettings;
import com.jizza.engine.api.mcp.McpServers;
import com.jizza.engine.api.mcp.McpToolDto;
import com.jizza.engine.api.tool.ToolResult;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.util.Redaction;
import com.jizza.engine.persistence.entity.McpServerEntity;
import com.jizza.engine.persistence.repository.McpServerRepository;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * Remote MCP servers added in the app: stored here, reached through {@link McpToolProvider}'s kept
 * sessions, and handed back to it as {@link McpProperties.Server}s for the owner's agents.
 *
 * <p>The tool list is what the server last said it offers; what the user decided about each tool
 * (on or off, what it does) is kept beside it, so a new tool from the server arrives on but
 * unclassified, which means the guardrails judge it as the most consequential kind (moving money).
 */
public class McpServerService implements McpServers, StoredMcpServers {

  private static final Logger log = LoggerFactory.getLogger(McpServerService.class);
  private static final int MAX_TOOLS = 200;

  private final McpServerRepository servers;
  private final McpToolProvider provider;
  private final EncryptionService encryption;
  private final McpOAuth oauth;

  public McpServerService(McpServerRepository servers, McpToolProvider provider,
                          EncryptionService encryption, McpOAuth oauth) {
    this.servers = servers;
    this.provider = provider;
    this.encryption = encryption;
    this.oauth = oauth;
    provider.setStoredServers(this);
  }

  // ── Storage ───────────────────────────────────────────────────────────────────

  @Override
  @Transactional(readOnly = true)
  public List<McpServerDto> list(UUID workspaceId) {
    return servers.findByWorkspaceIdOrderByNameAsc(workspaceId).stream().map(this::toDto).toList();
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<McpServerDto> find(UUID serverId) {
    return servers.findById(serverId).map(this::toDto);
  }

  @Override
  @Transactional
  public McpServerDto create(UUID userId, UUID workspaceId, McpServerSettings settings) {
    McpServerEntity entity = new McpServerEntity();
    entity.setUserId(userId);
    entity.setWorkspaceId(workspaceId);
    entity.setStatus("new");
    entity.setToolSettings(Json.obj());
    entity.setAgentKeys(Json.mapper().createArrayNode().add("jizza"));
    entity.setAuthType("none");
    entity.setSlug(uniqueSlug(userId, settings.name()));
    apply(entity, settings);
    return toDto(servers.save(entity));
  }

  @Override
  @Transactional
  public McpServerDto update(UUID serverId, McpServerSettings settings) {
    McpServerEntity entity = require(serverId);
    String url = entity.getUrl();
    String authType = entity.getAuthType();
    apply(entity, settings);
    if (!entity.getUrl().equals(url) || !entity.getAuthType().equals(authType)) {
      // Another server, or another way in: what was learnt about the old one no longer holds.
      clearTokens(entity);
      entity.setOauthAuthorizationUrl(null);
      entity.setOauthTokenUrl(null);
      entity.setOauthResource(null);
      entity.setStatus("new");
    }
    provider.forget(serverId);
    return toDto(servers.save(entity));
  }

  @Override
  @Transactional
  public void delete(UUID serverId) {
    servers.delete(require(serverId));
    provider.forget(serverId);
  }

  private void apply(McpServerEntity entity, McpServerSettings s) {
    if (s.name() != null) entity.setName(s.name().strip());
    if (s.url() != null) entity.setUrl(s.url().strip());
    if (s.authType() != null) entity.setAuthType(s.authType().name().toLowerCase(Locale.ROOT));
    if (s.headerName() != null) entity.setHeaderName(s.headerName().isBlank() ? null : s.headerName().strip());
    if (s.secret() != null) {
      entity.setSecret(s.secret().isBlank() ? null : encryption.encryptString(s.secret().strip()));
    }
    if (s.enabled() != null) entity.setEnabled(s.enabled());
    if (s.agentKeys() != null) {
      ArrayNode keys = Json.mapper().createArrayNode();
      s.agentKeys().stream().filter(k -> k != null && !k.isBlank()).map(String::strip).distinct()
          .forEach(keys::add);
      entity.setAgentKeys(keys);
    }
    if (s.redactArguments() != null) entity.setRedactArguments(s.redactArguments());
    if (s.oauthClientId() != null) {
      entity.setOauthClientId(s.oauthClientId().isBlank() ? null : s.oauthClientId().strip());
    }
    if (s.oauthClientSecret() != null) {
      entity.setOauthClientSecret(s.oauthClientSecret().isBlank() ? null
          : encryption.encryptString(s.oauthClientSecret().strip()));
    }
    if (s.oauthScopes() != null) {
      entity.setOauthScopes(s.oauthScopes().isBlank() ? null : s.oauthScopes().strip());
    }
  }

  // ── Tools ─────────────────────────────────────────────────────────────────────

  @Override
  @Transactional
  public McpServerDto refreshTools(UUID serverId) {
    McpServerEntity entity = require(serverId);
    if (authType(entity) == McpAuthType.OAUTH && entity.getAccessToken() == null) {
      entity.setStatus("needs_auth");
      entity.setLastError(null);
      return toDto(servers.save(entity));
    }
    try {
      McpProperties.Server server = toServer(entity);
      List<McpSchema.Tool> tools = provider.discover(entity.getSlug(), server);
      entity.setTools(toolsJson(tools));
      entity.setToolsRefreshedAt(Instant.now());
      McpSchema.Implementation info = provider.serverInfo(entity.getSlug(), server);
      if (info != null) {
        entity.setServerName(info.name());
        entity.setServerVersion(info.version());
      }
      entity.setStatus("connected");
      entity.setLastError(null);
    } catch (RuntimeException e) {
      String message = Redaction.scrub(describe(e));
      log.info("MCP server '{}' could not be reached: {}", entity.getName(), message, e);
      boolean unauthorised = message.contains("401") || message.contains("403")
          || message.toLowerCase(Locale.ROOT).contains("unauthorized");
      entity.setStatus(unauthorised && authType(entity) == McpAuthType.OAUTH ? "needs_auth" : "error");
      entity.setLastError(unauthorised
          ? "The server refused Jizza's sign-in. Check the token, or sign in again."
          : "Could not reach the server: " + abbreviate(message));
    }
    return toDto(servers.save(entity));
  }

  @Override
  @Transactional
  public McpServerDto updateTool(UUID serverId, String tool, Boolean enabled, ActionType actionType) {
    McpServerEntity entity = require(serverId);
    if (knownTools(entity).stream().noneMatch(t -> t.name().equals(tool))) {
      throw new NotFoundException("MCP tool", tool);
    }
    ObjectNode settings = entity.getToolSettings() instanceof ObjectNode o ? o.deepCopy() : Json.obj();
    ObjectNode one = settings.path(tool) instanceof ObjectNode o ? o : settings.putObject(tool);
    if (enabled != null) one.put("enabled", enabled);
    if (actionType != null) one.put("actionType", actionType.name().toLowerCase(Locale.ROOT));
    settings.set(tool, one);
    entity.setToolSettings(settings);
    return toDto(servers.save(entity));
  }

  @Override
  public ToolResult call(UUID serverId, String tool, JsonNode arguments) {
    McpServerEntity entity = servers.findById(serverId).orElse(null);
    if (entity == null) return ToolResult.error("That MCP server no longer exists");
    if (!entity.isEnabled()) return ToolResult.error(entity.getName() + " is turned off");
    McpProperties.Server server = ready(entity);
    if (server == null) {
      return ToolResult.error(entity.getName() + " needs signing in again before it can be used");
    }
    Map<String, Object> args = arguments == null || arguments.isNull() ? Map.of()
        : Json.mapper().convertValue(arguments, new TypeReference<Map<String, Object>>() { });
    return provider.callTool(entity.getSlug(), server, tool, args);
  }

  // ── Sign-in ───────────────────────────────────────────────────────────────────

  @Override
  @Transactional
  public URI startAuth(UUID serverId, UUID sessionId) {
    McpServerEntity entity = require(serverId);
    if (authType(entity) != McpAuthType.OAUTH) {
      throw new ValidationException("Only a server set to sign in with OAuth has a sign-in page");
    }
    URI url = oauth.start(entity, sessionId);
    servers.save(entity);
    return url;
  }

  /** A failed exchange still uses up the state, so the attempt cannot be replayed. */
  @Override
  @Transactional(noRollbackFor = com.jizza.engine.api.error.JizzaException.class)
  public McpServerDto completeAuth(String state, String code) {
    if (state == null || state.isBlank() || code == null || code.isBlank()) {
      throw new ValidationException("The sign-in callback needs both a state and a code");
    }
    McpServerEntity entity = servers.findByOauthState(state)
        .orElseThrow(() -> new AuthException("oauth-state",
            "That sign-in attempt is not recognised or was already used. Start it again."));
    try {
      oauth.complete(entity, code);
    } finally {
      servers.save(entity);
    }
    entity.setStatus("connected");
    provider.forget(entity.getId());
    servers.save(entity);
    return refreshTools(entity.getId());
  }

  @Override
  @Transactional
  public McpServerDto signOut(UUID serverId) {
    McpServerEntity entity = require(serverId);
    clearTokens(entity);
    entity.setStatus(authType(entity) == McpAuthType.OAUTH ? "needs_auth" : "new");
    provider.forget(serverId);
    return toDto(servers.save(entity));
  }

  private static void clearTokens(McpServerEntity entity) {
    entity.setAccessToken(null);
    entity.setRefreshToken(null);
    entity.setTokenExpiresAt(null);
    McpOAuth.clearState(entity);
  }

  // ── For the provider ──────────────────────────────────────────────────────────

  @Override
  public Map<String, McpProperties.Server> serversFor(UUID userId) {
    Map<String, McpProperties.Server> out = new LinkedHashMap<>();
    for (McpServerEntity entity : servers.findByUserIdOrderByNameAsc(userId)) {
      if (!entity.isEnabled()) continue;
      McpProperties.Server server = ready(entity);
      if (server != null) out.put(entity.getSlug(), server);
    }
    return out;
  }

  /** The server as the provider uses it, with a live token; null when it needs signing in. */
  private McpProperties.Server ready(McpServerEntity entity) {
    if (authType(entity) == McpAuthType.OAUTH) {
      if (entity.getAccessToken() == null) return null;
      if (oauth.needsRefresh(entity)) {
        try {
          oauth.refresh(entity);
          servers.save(entity);
        } catch (RuntimeException e) {
          log.info("Refreshing the sign-in of MCP server '{}' failed: {}", entity.getName(),
              e.getMessage());
          clearTokens(entity);
          entity.setStatus("needs_auth");
          entity.setLastError("The sign-in expired. Sign in again.");
          servers.save(entity);
          return null;
        }
      }
    }
    McpProperties.Server server = toServer(entity);
    List<McpSchema.Tool> offered = new ArrayList<>();
    List<String> names = new ArrayList<>();
    JsonNode settings = entity.getToolSettings() == null ? Json.obj() : entity.getToolSettings();
    for (McpSchema.Tool tool : knownTools(entity)) {
      JsonNode one = settings.path(tool.name());
      if (!one.path("enabled").asBoolean(true)) continue;
      offered.add(tool);
      names.add(tool.name());
      actionType(one).ifPresent(type -> server.getActionTypes().put(tool.name(), type));
    }
    server.setKnownTools(offered);
    // An empty list would offer every tool; with nothing on, name one nobody has.
    server.setTools(names.isEmpty() ? List.of("\u0000none") : names);
    return server;
  }

  private McpProperties.Server toServer(McpServerEntity entity) {
    McpProperties.Server server = new McpProperties.Server();
    server.setId(entity.getId());
    server.setWorkspaceId(entity.getWorkspaceId());
    server.setTransport(McpProperties.Transport.HTTP);
    server.setUrl(entity.getUrl());
    server.setEnabled(entity.isEnabled());
    server.setRedactArguments(entity.isRedactArguments());
    List<String> agents = new ArrayList<>();
    if (entity.getAgentKeys() != null) entity.getAgentKeys().forEach(k -> agents.add(k.asText()));
    server.setAgents(agents);
    Map<String, String> headers = new HashMap<>();
    switch (authType(entity)) {
      case BEARER -> {
        String token = encryption.decryptString(entity.getSecret());
        if (token != null) headers.put("Authorization", "Bearer " + token);
      }
      case HEADER -> {
        String value = encryption.decryptString(entity.getSecret());
        if (value != null && entity.getHeaderName() != null) headers.put(entity.getHeaderName(), value);
      }
      case OAUTH -> {
        String token = oauth.accessToken(entity);
        if (token != null) headers.put("Authorization", "Bearer " + token);
      }
      case NONE -> { }
    }
    server.setHeaders(headers);
    return server;
  }

  // ── Translation ───────────────────────────────────────────────────────────────

  private static ArrayNode toolsJson(List<McpSchema.Tool> tools) {
    ArrayNode out = Json.mapper().createArrayNode();
    for (McpSchema.Tool tool : tools.stream().limit(MAX_TOOLS).toList()) {
      ObjectNode one = out.addObject().put("name", tool.name());
      if (tool.title() != null) one.put("title", tool.title());
      if (tool.description() != null) one.put("description", tool.description());
      one.set("inputSchema", tool.inputSchema() == null ? Json.obj() : Json.toNode(tool.inputSchema()));
      if (tool.annotations() != null) {
        if (Boolean.TRUE.equals(tool.annotations().readOnlyHint())) one.put("hint", "read-only");
        else if (Boolean.TRUE.equals(tool.annotations().destructiveHint())) one.put("hint", "destructive");
      }
    }
    return out;
  }

  private static List<McpSchema.Tool> knownTools(McpServerEntity entity) {
    if (entity.getTools() == null || !entity.getTools().isArray()) return List.of();
    List<McpSchema.Tool> tools = new ArrayList<>();
    for (JsonNode one : entity.getTools()) {
      Map<String, Object> schema = Json.mapper().convertValue(one.path("inputSchema"),
          new TypeReference<Map<String, Object>>() { });
      tools.add(McpSchema.Tool.builder().name(one.path("name").asText())
          .title(one.path("title").asText(null))
          .description(one.path("description").asText(null))
          .inputSchema(schema == null ? Map.of("type", "object") : schema).build());
    }
    return tools;
  }

  private McpServerDto toDto(McpServerEntity e) {
    List<String> agents = new ArrayList<>();
    if (e.getAgentKeys() != null) e.getAgentKeys().forEach(k -> agents.add(k.asText()));
    JsonNode settings = e.getToolSettings() == null ? Json.obj() : e.getToolSettings();
    List<McpToolDto> tools = new ArrayList<>();
    if (e.getTools() != null && e.getTools().isArray()) {
      for (JsonNode one : e.getTools()) {
        String name = one.path("name").asText();
        JsonNode s = settings.path(name);
        tools.add(new McpToolDto(name, McpToolProvider.toolName(e.getSlug(), name),
            one.path("title").asText(null), one.path("description").asText(null),
            one.path("inputSchema"), s.path("enabled").asBoolean(true), actionType(s).orElse(null),
            one.path("hint").asText(null)));
      }
    }
    return new McpServerDto(e.getId(), e.getUserId(), e.getWorkspaceId(), e.getName(), e.getSlug(),
        e.getUrl(), authType(e), e.getHeaderName(), e.getSecret() != null,
        e.getAccessToken() != null, e.isEnabled(), agents, e.isRedactArguments(), e.getStatus(),
        e.getLastError(), e.getServerName(), e.getServerVersion(), tools, e.getToolsRefreshedAt(),
        e.getCreatedAt());
  }

  private static Optional<ActionType> actionType(JsonNode toolSettings) {
    String raw = toolSettings.path("actionType").asText(null);
    if (raw == null) return Optional.empty();
    try {
      return Optional.of(ActionType.valueOf(raw.toUpperCase(Locale.ROOT)));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  private static McpAuthType authType(McpServerEntity entity) {
    return McpAuthType.valueOf(entity.getAuthType().toUpperCase(Locale.ROOT));
  }

  private McpServerEntity require(UUID serverId) {
    return servers.findById(serverId).orElseThrow(() -> new NotFoundException("MCP server", serverId));
  }

  /** "Zerodha Kite" becomes {@code zerodha_kite}; a second one {@code zerodha_kite_2}. */
  private String uniqueSlug(UUID userId, String name) {
    String base = (name == null ? "" : name).toLowerCase(Locale.ROOT)
        .replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
    if (base.isEmpty()) base = "server";
    if (base.length() > 24) base = base.substring(0, 24);
    String slug = base;
    for (int n = 2; servers.existsByUserIdAndSlug(userId, slug); n++) slug = base + "_" + n;
    return slug;
  }

  /**
   * The SDK wraps the reason in "Client failed to initialize ...", which says nothing; the causes
   * underneath say what the server answered.
   */
  static String describe(Throwable error) {
    List<String> parts = new ArrayList<>();
    for (Throwable t = error; t != null && parts.size() < 4; t = t.getCause()) {
      String message = t.getMessage();
      if (message == null || message.isBlank() || message.startsWith("Client failed to initialize")) {
        continue;
      }
      if (parts.stream().noneMatch(p -> p.contains(message))) parts.add(message.strip());
    }
    return parts.isEmpty() ? String.valueOf(error.getMessage()) : String.join(": ", parts);
  }

  private static String abbreviate(String text) {
    return text.length() <= 300 ? text : text.substring(0, 300) + "…";
  }
}
