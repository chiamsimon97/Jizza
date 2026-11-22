package com.jizza.platform.usecase.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.authz.Access;
import com.jizza.engine.api.enums.ActionType;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.mcp.McpAuthType;
import com.jizza.engine.api.mcp.McpServerDto;
import com.jizza.engine.api.mcp.McpServerSettings;
import com.jizza.engine.api.mcp.McpServers;
import com.jizza.engine.api.orchestrator.AgentDirectory;
import com.jizza.engine.api.tool.ToolResult;
import com.jizza.engine.api.util.Json;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Adding a remote MCP server as a connector, the way Claude's custom connectors work: a name and
 * the server's URL, how it signs in, and which agents may use it. Its tools then reach those agents
 * in chat and the workflow builder's "MCP tool" step, always through the guardrails.
 *
 * <p>What is decided here: what a usable server looks like, that only the person's own agents can
 * be named, that a server from another account reads as missing, and what is worth auditing.
 */
@Service
public class McpServerUseCase {

  private static final Pattern HEADER_NAME = Pattern.compile("[A-Za-z0-9-]{1,64}");
  /** Headers the HTTP client or the MCP transport sets itself. */
  private static final Set<String> RESERVED_HEADERS = Set.of("host", "content-length",
      "content-type", "accept", "connection", "mcp-session-id", "mcp-protocol-version",
      "transfer-encoding", "upgrade", "expect");

  /** A server's name in the desktop app it runs on. */
  private static final Pattern DEVICE_SERVER = Pattern.compile("[A-Za-z0-9._-]{1,64}");
  private static final Set<String> TRANSPORTS = Set.of(McpServerSettings.STREAMABLE_HTTP,
      McpServerSettings.SSE, McpServerSettings.DEVICE);

  private final McpServers servers;
  private final AgentDirectory agents;
  private final AuditLog audit;
  private org.springframework.beans.factory.ObjectProvider<com.jizza.platform.usecase.device.DeviceUseCase> deviceUseCase;

  /** Where a server that runs on a paired desktop app is checked to be the person's own. */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setDeviceUseCase(
      org.springframework.beans.factory.ObjectProvider<com.jizza.platform.usecase.device.DeviceUseCase> devices) {
    this.deviceUseCase = devices;
  }

  public McpServerUseCase(McpServers servers, AgentDirectory agents, AuditLog audit) {
    this.servers = servers;
    this.agents = agents;
    this.audit = audit;
  }

  public List<McpServerDto> list(Access access) {
    return servers.list(access.workspaceId());
  }

  public McpServerDto get(Access access, UUID serverId) {
    return owned(access, serverId);
  }

  /** Saves the server, then reaches it once so the person sees its tools straight away. */
  public McpServerDto create(Access access, McpServerSettings in) {
    if (isBlank(in.name())) throw new ValidationException("Give the MCP server a name");
    String transport = in.transport() == null ? McpServerSettings.STREAMABLE_HTTP : in.transport();
    boolean onDevice = McpServerSettings.DEVICE.equals(transport);
    if (!onDevice && isBlank(in.url())) throw new ValidationException("Give the MCP server's URL");
    McpAuthType auth = in.authType() == null ? McpAuthType.NONE : in.authType();
    validate(access, in, auth, true);
    checkTransport(access, transport, in.deviceId(), in.deviceServer(), auth);
    McpServerDto created = servers.create(access.userId(), access.workspaceId(),
        new McpServerSettings(in.name(), onDevice ? null : in.url(), auth, in.headerName(),
            in.secret(), in.enabled(), in.agentKeys(), in.redactArguments(), in.oauthClientId(),
            in.oauthClientSecret(), in.oauthScopes(), transport, onDevice ? in.deviceId() : null,
            onDevice ? in.deviceServer().strip() : null));
    audit.record(access.userId(), ActorType.USER, null, "mcp_server.created", created.name(),
        Json.obj().put("serverId", created.id().toString()).put("url", created.url())
            .put("auth", auth.name().toLowerCase(Locale.ROOT)));
    return auth == McpAuthType.OAUTH ? created : servers.refreshTools(created.id());
  }

  public McpServerDto update(Access access, UUID serverId, McpServerSettings in) {
    McpServerDto existing = owned(access, serverId);
    McpAuthType auth = in.authType() == null ? existing.authType() : in.authType();
    validate(access, in, auth, false);
    String transport = in.transport() == null ? existing.transport() : in.transport();
    if (in.transport() != null || in.deviceId() != null || in.deviceServer() != null
        || in.authType() != null) {
      boolean onDevice = McpServerSettings.DEVICE.equals(transport);
      checkTransport(access, transport,
          onDevice && in.deviceId() == null ? existing.deviceId() : in.deviceId(),
          onDevice && in.deviceServer() == null ? existing.deviceServer() : in.deviceServer(), auth);
      if (onDevice && in.url() != null) {
        throw new ValidationException("A server on your computer has no URL");
      }
    }
    if (auth == McpAuthType.HEADER && isBlank(in.headerName()) && isBlank(existing.headerName())) {
      throw new ValidationException("Name the header the secret is sent in");
    }
    if ((auth == McpAuthType.BEARER || auth == McpAuthType.HEADER) && auth != existing.authType()
        && isBlank(in.secret())) {
      throw new ValidationException("Enter the token or secret for the server");
    }
    McpServerDto updated = servers.update(serverId, in);
    audit.record(access.userId(), ActorType.USER, null, "mcp_server.updated", updated.name(),
        Json.obj().put("serverId", serverId.toString())
            .put("secretChanged", in.secret() != null)
            .put("agentsChanged", in.agentKeys() != null));
    return updated;
  }

  public void delete(Access access, UUID serverId) {
    McpServerDto existing = owned(access, serverId);
    servers.delete(serverId);
    audit.record(access.userId(), ActorType.USER, null, "mcp_server.deleted", existing.name(),
        Json.obj().put("serverId", serverId.toString()));
  }

  public McpServerDto refreshTools(Access access, UUID serverId) {
    owned(access, serverId);
    return servers.refreshTools(serverId);
  }

  public McpServerDto updateTool(Access access, UUID serverId, String tool, Boolean enabled,
                                 ActionType actionType) {
    McpServerDto existing = owned(access, serverId);
    McpServerDto updated = servers.updateTool(serverId, tool, enabled, actionType);
    var detail = Json.obj().put("serverId", serverId.toString()).put("tool", tool);
    if (enabled != null) detail.put("enabled", enabled);
    if (actionType != null) detail.put("actionType", actionType.name().toLowerCase(Locale.ROOT));
    audit.record(access.userId(), ActorType.USER, null, "mcp_server.tool_updated",
        existing.name(), detail);
    return updated;
  }

  /**
   * The review after a server is added: says what several tools do at once. Each type given becomes
   * the person's own, so the guardrails stop treating it as a guess.
   */
  public McpServerDto updateToolTypes(Access access, UUID serverId,
                                      java.util.Map<String, ActionType> types) {
    McpServerDto existing = owned(access, serverId);
    if (types == null || types.isEmpty()) return existing;
    for (var e : types.entrySet()) {
      if (e.getValue() == null) throw new ValidationException("Choose a type for " + e.getKey());
      if (existing.tools().stream().noneMatch(t -> t.name().equals(e.getKey()))) {
        throw new NotFoundException("MCP tool", e.getKey());
      }
    }
    McpServerDto updated = servers.updateToolTypes(serverId, types);
    var counts = Json.obj();
    types.values().forEach(t -> {
      String k = t.name().toLowerCase(Locale.ROOT);
      counts.put(k, counts.path(k).asInt() + 1);
    });
    audit.record(access.userId(), ActorType.USER, null, "mcp_server.tool_types", existing.name(),
        Json.obj().put("serverId", serverId.toString()).set("types", counts));
    return updated;
  }

  public URI startAuth(Access access, UUID serverId, UUID sessionId) {
    McpServerDto existing = owned(access, serverId);
    URI url = servers.startAuth(serverId, sessionId);
    audit.record(access.userId(), ActorType.USER, null, "mcp_server.auth_started",
        existing.name(), Json.obj().put("serverId", serverId.toString()));
    return url;
  }

  /** The browser's redirect: the state, not a session, is what authenticates it. */
  public McpServerDto completeAuth(String state, String code) {
    McpServerDto server = servers.completeAuth(state, code);
    audit.record(server.userId(), ActorType.USER, null, "mcp_server.connected", server.name(),
        Json.obj().put("serverId", server.id().toString()));
    return server;
  }

  public McpServerDto signOut(Access access, UUID serverId) {
    McpServerDto existing = owned(access, serverId);
    McpServerDto updated = servers.signOut(serverId);
    audit.record(access.userId(), ActorType.USER, null, "mcp_server.signed_out", existing.name(),
        Json.obj().put("serverId", serverId.toString()));
    return updated;
  }

  /**
   * Runs one tool by hand from the server's page, as testing a service action does. The person is
   * the one acting, so it is audited as theirs rather than judged as an agent's call.
   */
  public ToolResult test(Access access, UUID serverId, String tool, JsonNode arguments) {
    McpServerDto existing = owned(access, serverId);
    if (existing.tools().stream().noneMatch(t -> t.name().equals(tool))) {
      throw new NotFoundException("MCP tool", tool);
    }
    audit.record(access.userId(), ActorType.USER, null, "mcp_server.tool_tested", existing.name(),
        Json.obj().put("serverId", serverId.toString()).put("tool", tool));
    ToolResult result = servers.call(serverId, tool, arguments == null ? Json.obj() : arguments);
    // A test run is how a tool's output fields are learnt, as a test step is in Zapier.
    if (result.ok()) servers.learnOutput(serverId, tool, result.output());
    return result;
  }

  /** A server in another account is reported missing, so ids cannot be probed across accounts. */
  public McpServerDto owned(Access access, UUID serverId) {
    return servers.find(serverId)
        .filter(s -> s.workspaceId().equals(access.workspaceId()))
        .orElseThrow(() -> new NotFoundException("MCP server", serverId));
  }

  private void validate(Access access, McpServerSettings in, McpAuthType auth, boolean creating) {
    if (in.name() != null && (in.name().isBlank() || in.name().strip().length() > 60)) {
      throw new ValidationException("The name must be 1 to 60 characters");
    }
    if (in.url() != null) validateUrl(in.url().strip());
    if (creating && (auth == McpAuthType.BEARER || auth == McpAuthType.HEADER) && isBlank(in.secret())) {
      throw new ValidationException("Enter the token or secret for the server");
    }
    if (auth == McpAuthType.HEADER && (creating || in.headerName() != null)) {
      String header = in.headerName() == null ? "" : in.headerName().strip();
      if (!HEADER_NAME.matcher(header).matches()) {
        throw new ValidationException("Name the header the secret is sent in, such as X-API-Key");
      }
      if (RESERVED_HEADERS.contains(header.toLowerCase(Locale.ROOT))) {
        throw new ValidationException("Jizza sets the " + header + " header itself; use another");
      }
    }
    if (in.agentKeys() != null) {
      for (String key : in.agentKeys()) {
        if (key == null || key.isBlank() || key.equals("*")) continue;
        if (agents.byKey(access.userId(), key.strip()).isEmpty()) {
          throw new ValidationException("There is no agent '" + key + "' to give this server to");
        }
      }
    }
  }

  /**
   * SSE and streamable HTTP are web addresses; a server on a paired desktop app names the app and
   * the server as set up there, signs in on its own, and must be on one of the person's own
   * computers.
   */
  private void checkTransport(Access access, String transport, UUID deviceId, String deviceServer,
                              McpAuthType auth) {
    if (!TRANSPORTS.contains(transport)) {
      throw new ValidationException("Choose how Jizza reaches the server: streamable_http, sse or "
          + "device");
    }
    if (!McpServerSettings.DEVICE.equals(transport)) return;
    if (deviceId == null) throw new ValidationException("Choose the computer the server runs on");
    if (deviceServer == null || !DEVICE_SERVER.matcher(deviceServer.strip()).matches()) {
      throw new ValidationException("Give the server's name as it is set up in the Jizza app on "
          + "that computer");
    }
    if (auth != McpAuthType.NONE) {
      throw new ValidationException("A server on your computer signs in there, not in Jizza");
    }
    var devices = deviceUseCase == null ? null : deviceUseCase.getIfAvailable();
    if (devices == null) throw new ValidationException("Paired computers aren't available here");
    var device = devices.get(access, deviceId);
    if (device.platform() == null || !DESKTOPS.contains(device.platform().name())) {
      throw new ValidationException(device.name() + " can't run MCP servers; choose a computer "
          + "with the Jizza desktop app");
    }
  }

  private static final Set<String> DESKTOPS = Set.of("WINDOWS", "MACOS", "LINUX");

  private static void validateUrl(String url) {
    URI uri;
    try {
      uri = URI.create(url);
    } catch (IllegalArgumentException e) {
      throw new ValidationException("That is not a URL");
    }
    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    if (!scheme.equals("https") && !scheme.equals("http")) {
      throw new ValidationException("The server's URL must start with https://");
    }
    if (uri.getHost() == null) throw new ValidationException("The server's URL needs a host");
    if (uri.getRawUserInfo() != null) {
      throw new ValidationException("Put credentials in the sign-in settings, not in the URL");
    }
    if (uri.getRawFragment() != null) throw new ValidationException("The URL cannot have a #fragment");
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
