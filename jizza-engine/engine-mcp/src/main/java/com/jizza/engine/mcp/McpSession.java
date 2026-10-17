package com.jizza.engine.mcp;

import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;

/** One open connection to an MCP server: what it offers, and calling it. */
public interface McpSession extends AutoCloseable {

  List<McpSchema.Tool> listTools();

  McpSchema.CallToolResult callTool(String name, Map<String, Object> arguments);

  @Override
  void close();

  /** Opens a session to a configured server. */
  @FunctionalInterface
  interface Factory {
    McpSession open(String name, McpProperties.Server server);
  }
}
