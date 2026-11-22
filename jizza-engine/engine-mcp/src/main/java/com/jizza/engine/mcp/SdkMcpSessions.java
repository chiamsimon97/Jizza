package com.jizza.engine.mcp;

import com.jizza.engine.api.util.Json;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sessions on the official MCP Java SDK.
 *
 * <p>The client declares no capabilities: a server cannot ask Jizza's model to write for it
 * (sampling) or ask the user questions (elicitation). Both would reach the model or the user
 * outside the agent loop, where budgets, guardrails and the audit log do not see them.
 */
public class SdkMcpSessions implements McpSession.Factory {

  private static final Logger log = LoggerFactory.getLogger(SdkMcpSessions.class);

  private final McpJsonMapper mapper = new JacksonMcpJsonMapper(Json.mapper());

  @Override
  public McpSession open(String name, McpProperties.Server server) {
    McpSyncClient client = McpClient.sync(transport(name, server))
        .requestTimeout(server.getRequestTimeout())
        .initializationTimeout(server.getRequestTimeout())
        .clientInfo(new McpSchema.Implementation("jizza", "1.0"))
        .build();
    client.initialize();
    log.info("Connected to MCP server '{}' ({})", name, client.getServerInfo() == null
        ? "unnamed" : client.getServerInfo().name());
    return new SdkSession(client);
  }

  private McpClientTransport transport(String name, McpProperties.Server server) {
    return switch (server.getTransport()) {
      case STDIO -> {
        if (server.getCommand() == null || server.getCommand().isBlank()) {
          throw new IllegalStateException("MCP server '" + name + "' has no command to start");
        }
        ServerParameters parameters = ServerParameters.builder(server.getCommand())
            .args(server.getArgs())
            .env(server.getEnv())
            .build();
        StdioClientTransport stdio = new StdioClientTransport(parameters, mapper);
        // What a server prints to stderr is its own diagnostics; it is logged, never shown to a model.
        stdio.setStdErrorHandler(line -> log.debug("MCP server '{}': {}", name, line));
        yield stdio;
      }
      case HTTP -> {
        if (server.getUrl() == null || server.getUrl().isBlank()) {
          throw new IllegalStateException("MCP server '" + name + "' has no url");
        }
        java.net.URI uri = java.net.URI.create(server.getUrl());
        String base = uri.getScheme() + "://" + uri.getRawAuthority();
        String endpoint = uri.getRawPath() == null || uri.getRawPath().isEmpty()
            ? "/mcp" : uri.getRawPath();
        java.net.http.HttpRequest.Builder request = java.net.http.HttpRequest.newBuilder();
        server.getHeaders().forEach(request::header);
        yield HttpClientStreamableHttpTransport.builder(base)
            .endpoint(endpoint)
            .jsonMapper(mapper)
            .requestBuilder(request)
            // JSON is UTF-8 by definition; some servers (mcp-go, behind Zerodha Kite) refuse
            // anything but the bare media type, and the SDK adds "; charset=utf-8".
            .httpRequestCustomizer((builder, method, endpointUri, body, context) -> {
              if ("POST".equals(method)) builder.setHeader("Content-Type", "application/json");
            })
            .connectTimeout(server.getRequestTimeout())
            .build();
      }
      case SSE -> {
        // The older HTTP+SSE transport (protocol 2024-11-05): an event stream to listen on, and an
        // address it names to post messages to, which must be on the same server.
        if (server.getUrl() == null || server.getUrl().isBlank()) {
          throw new IllegalStateException("MCP server '" + name + "' has no url");
        }
        java.net.URI uri = java.net.URI.create(server.getUrl());
        String base = uri.getScheme() + "://" + uri.getRawAuthority();
        String endpoint = uri.getRawPath() == null || uri.getRawPath().isEmpty()
            ? "/sse" : uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        java.net.http.HttpRequest.Builder request = java.net.http.HttpRequest.newBuilder();
        server.getHeaders().forEach(request::header);
        yield HttpClientSseClientTransport.builder(base)
            .sseEndpoint(endpoint)
            .jsonMapper(mapper)
            .requestBuilder(request)
            .httpRequestCustomizer((builder, method, endpointUri, body, context) -> {
              if ("POST".equals(method)) builder.setHeader("Content-Type", "application/json");
            })
            .connectTimeout(server.getRequestTimeout())
            .build();
      }
      case DEVICE -> throw new IllegalStateException("MCP server '" + name
          + "' runs on a paired app, which this session factory cannot reach");
    };
  }

  private static final class SdkSession implements McpSession {
    private final McpSyncClient client;

    SdkSession(McpSyncClient client) { this.client = client; }

    @Override
    public List<McpSchema.Tool> listTools() {
      List<McpSchema.Tool> all = new ArrayList<>();
      String cursor = null;
      // Bounded, so a server that never stops paging cannot hold the turn.
      for (int page = 0; page < 20; page++) {
        McpSchema.ListToolsResult result = cursor == null ? client.listTools()
            : client.listTools(cursor);
        all.addAll(result.tools());
        cursor = result.nextCursor();
        if (cursor == null || cursor.isBlank()) break;
      }
      return all;
    }

    @Override
    public McpSchema.CallToolResult callTool(String name, Map<String, Object> arguments) {
      return client.callTool(new McpSchema.CallToolRequest(name, arguments));
    }

    @Override
    public McpSchema.Implementation serverInfo() { return client.getServerInfo(); }

    @Override
    public void close() {
      try {
        client.closeGracefully();
      } catch (RuntimeException e) {
        client.close();
      }
    }
  }
}
