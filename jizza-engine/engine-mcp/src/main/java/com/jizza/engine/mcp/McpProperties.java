package com.jizza.engine.mcp;

import com.jizza.engine.api.enums.ActionType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The MCP servers this installation uses, written by its owner. Only configuration can start a
 * process on this machine ({@code stdio}): a web form or a model never can. Remote servers can also
 * be added from the app ({@link StoredMcpServers}); those are HTTP only, pass the egress guard, and
 * reach the provider as the same {@link Server} shape with their id set.
 *
 * <pre>
 * jizza:
 *   mcp:
 *     servers:
 *       docs:
 *         transport: http
 *         url: http://localhost:3333/mcp
 *         agents: [professional]
 *         action-types:
 *           search_docs: read
 *       files:
 *         transport: stdio
 *         command: npx
 *         args: [-y, "@modelcontextprotocol/server-filesystem", /Users/me/Notes]
 *         agents: [jizza, professional]
 *         tools: [read_text_file, list_directory]
 *         action-types:
 *           read_text_file: read
 *           list_directory: read
 * </pre>
 */
@ConfigurationProperties(prefix = "jizza.mcp")
public class McpProperties {

  /** By name; the name becomes part of every tool name the model sees. */
  private Map<String, Server> servers = new LinkedHashMap<>();

  /**
   * STDIO starts a program here; HTTP is streamable HTTP; SSE is the older HTTP+SSE transport;
   * DEVICE is a server that runs on a paired desktop app and is reached through device commands.
   */
  public enum Transport { STDIO, HTTP, SSE, DEVICE }

  public static class Server {
    private boolean enabled = true;
    private Transport transport = Transport.STDIO;

    /** STDIO: the program to start, its arguments and extra environment. */
    private String command;
    private List<String> args = new ArrayList<>();
    private Map<String, String> env = new HashMap<>();

    /** HTTP: the streamable-HTTP endpoint and any headers it needs, such as authorization. */
    private String url;
    private Map<String, String> headers = new HashMap<>();

    /**
     * Keys of the agents that may use this server ({@code finance}, {@code health}, ...), or
     * {@code *} for every agent. Empty means no agent: a server is not offered to anyone until the
     * owner says to whom, as a connector tool is not reachable until it is assigned.
     */
    private List<String> agents = new ArrayList<>();

    /** The server's tools that are offered, by their own name. Empty offers all of them. */
    private List<String> tools = new ArrayList<>();

    /**
     * What each tool does, by its own name, as the owner judges it. A tool not listed is treated
     * as the most consequential kind, so it asks before every call; what the server says about its
     * own tools is never taken on trust.
     */
    private Map<String, ActionType> actionTypes = new HashMap<>();

    /** Keep the arguments of calls out of the audit log, for servers handed personal detail. */
    private boolean redactArguments;

    private Duration requestTimeout = Duration.ofSeconds(30);

    /** How long a server's tool list is reused before it is asked again. */
    private Duration toolsCacheTtl = Duration.ofMinutes(5);

    // Set only for a server added from the app, never from configuration.
    private java.util.UUID id;
    /** DEVICE: the paired app the server runs on, its name there, and whose device it is. */
    private java.util.UUID deviceId;
    private String deviceServer;
    private java.util.UUID ownerId;
    private java.util.UUID workspaceId;
    /** The tools the user reviewed and left on; offered without asking the server each turn. */
    private List<io.modelcontextprotocol.spec.McpSchema.Tool> knownTools;

    public java.util.UUID getDeviceId() { return deviceId; }
    public void setDeviceId(java.util.UUID v) { this.deviceId = v; }
    public String getDeviceServer() { return deviceServer; }
    public void setDeviceServer(String v) { this.deviceServer = v; }
    public java.util.UUID getOwnerId() { return ownerId; }
    public void setOwnerId(java.util.UUID v) { this.ownerId = v; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { this.enabled = v; }
    public Transport getTransport() { return transport; }
    public void setTransport(Transport v) { this.transport = v; }
    public String getCommand() { return command; }
    public void setCommand(String v) { this.command = v; }
    public List<String> getArgs() { return args; }
    public void setArgs(List<String> v) { this.args = v; }
    public Map<String, String> getEnv() { return env; }
    public void setEnv(Map<String, String> v) { this.env = v; }
    public String getUrl() { return url; }
    public void setUrl(String v) { this.url = v; }
    public Map<String, String> getHeaders() { return headers; }
    public void setHeaders(Map<String, String> v) { this.headers = v; }
    public List<String> getAgents() { return agents; }
    public void setAgents(List<String> v) { this.agents = v; }
    public List<String> getTools() { return tools; }
    public void setTools(List<String> v) { this.tools = v; }
    public Map<String, ActionType> getActionTypes() { return actionTypes; }
    public void setActionTypes(Map<String, ActionType> v) { this.actionTypes = v; }
    public boolean isRedactArguments() { return redactArguments; }
    public void setRedactArguments(boolean v) { this.redactArguments = v; }
    public Duration getRequestTimeout() { return requestTimeout; }
    public void setRequestTimeout(Duration v) { this.requestTimeout = v; }
    public Duration getToolsCacheTtl() { return toolsCacheTtl; }
    public void setToolsCacheTtl(Duration v) { this.toolsCacheTtl = v; }

    public java.util.UUID getId() { return id; }
    public void setId(java.util.UUID v) { this.id = v; }
    public java.util.UUID getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(java.util.UUID v) { this.workspaceId = v; }
    public List<io.modelcontextprotocol.spec.McpSchema.Tool> getKnownTools() { return knownTools; }
    public void setKnownTools(List<io.modelcontextprotocol.spec.McpSchema.Tool> v) { this.knownTools = v; }

    /** What a session to this server depends on; when it changes the session is opened afresh. */
    String fingerprint() {
      return transport + "|" + url + "|" + new java.util.TreeMap<>(headers) + "|" + command + "|"
          + args + "|" + new java.util.TreeMap<>(env);
    }

    boolean offeredTo(String agentKey) {
      return enabled && agentKey != null && (agents.contains("*") || agents.contains(agentKey));
    }

    boolean offers(String toolName) {
      return tools.isEmpty() || tools.contains(toolName);
    }
  }

  public Map<String, Server> getServers() { return servers; }
  public void setServers(Map<String, Server> v) { this.servers = v; }
}
