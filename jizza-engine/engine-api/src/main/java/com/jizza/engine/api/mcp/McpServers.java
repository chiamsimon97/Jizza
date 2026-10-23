package com.jizza.engine.api.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.enums.ActionType;
import com.jizza.engine.api.tool.ToolResult;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Remote MCP servers the user adds from the app, like custom connectors in Claude: storage,
 * sign-in, tool discovery, and calling a tool by the server's own name.
 *
 * <p>Policy-free, as {@link com.jizza.engine.api.connector.ConnectionStore} is: what makes a valid
 * server and what is audited is the platform's business. Every outbound address still passes the
 * egress guard, and a call made through {@link #call} is not judged by the guardrails here: the
 * caller (the Capability Registry, a workflow step) does that first.
 */
public interface McpServers {

  List<McpServerDto> list(UUID workspaceId);

  Optional<McpServerDto> find(UUID serverId);

  McpServerDto create(UUID userId, UUID workspaceId, McpServerSettings settings);

  McpServerDto update(UUID serverId, McpServerSettings settings);

  void delete(UUID serverId);

  /** Connects, asks the server for its tools and keeps the list. Never throws for a bad server. */
  McpServerDto refreshTools(UUID serverId);

  /** Turns one tool on or off and says what it does; a null leaves that part alone. */
  McpServerDto updateTool(UUID serverId, String tool, Boolean enabled, ActionType actionType);

  /** OAUTH servers: discovers the server's authorization server and returns where to sign in. */
  URI startAuth(UUID serverId, UUID sessionId);

  /** The OAuth redirect: single-use, expiring state, then the code exchange. */
  McpServerDto completeAuth(String state, String code);

  /** Forgets the server's tokens and closes its session. */
  McpServerDto signOut(UUID serverId);

  /** Calls one of the server's tools by its own name. The caller has already judged the call. */
  ToolResult call(UUID serverId, String tool, JsonNode arguments);

  /**
   * Keeps the shape of what a tool returned as its output fields, for a tool whose server does
   * not declare them, so workflow steps after it can map them. Only the shape is kept.
   */
  default void learnOutput(UUID serverId, String tool, JsonNode output) { }
}
