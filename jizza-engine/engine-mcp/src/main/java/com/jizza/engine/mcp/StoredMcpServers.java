package com.jizza.engine.mcp;

import java.util.Map;
import java.util.UUID;

/** The MCP servers a person added in the app, in the shape the provider uses for configured ones. */
public interface StoredMcpServers {

  /**
   * The person's enabled servers that are ready to use, by the name their tools carry. A server
   * that still needs signing in is left out, as is one whose address the egress guard refuses.
   */
  Map<String, McpProperties.Server> serversFor(UUID userId);
}
