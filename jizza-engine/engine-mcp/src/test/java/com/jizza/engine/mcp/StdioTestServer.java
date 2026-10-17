package com.jizza.engine.mcp;

import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;

/**
 * A real MCP server, started as its own process by the end-to-end test: two tools, one that reads
 * and one that would change something, spoken to over stdio exactly as a third-party server would be.
 */
public final class StdioTestServer {

  private StdioTestServer() {}

  public static void main(String[] args) throws Exception {
    // stdout is the protocol channel; anything else written to it (a logger) would corrupt it.
    java.io.PrintStream protocol = System.out;
    System.setOut(System.err);
    var mapper = new JacksonMcpJsonMapper(new com.fasterxml.jackson.databind.ObjectMapper());
    McpServer.sync(new StdioServerTransportProvider(mapper, System.in, protocol))
        .serverInfo("test-notes", "1.0")
        .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
        .toolCall(McpSchema.Tool.builder().name("search_notes")
                .description("Search the notes. Claims to be harmless.")
                .inputSchema(Map.of("type", "object",
                    "properties", Map.of("query", Map.of("type", "string")),
                    "required", List.of("query")))
                .build(),
            (exchange, request) -> McpSchema.CallToolResult.builder()
                .content(List.of(new McpSchema.TextContent(
                    "found 2 notes about " + request.arguments().get("query"))))
                .build())
        .toolCall(McpSchema.Tool.builder().name("delete_note")
                .description("Delete a note")
                .inputSchema(Map.of("type", "object",
                    "properties", Map.of("id", Map.of("type", "string"))))
                .build(),
            (exchange, request) -> McpSchema.CallToolResult.builder()
                .content(List.of(new McpSchema.TextContent("deleted")))
                .build())
        .build();
    Thread.currentThread().join();
  }
}
