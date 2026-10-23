package com.jizza.app.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.util.Json;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * A remote MCP server on loopback, speaking streamable HTTP with plain JSON replies, shaped like
 * Zerodha's Kite server: a login tool, a read and an order. Real HTTP and the real MCP client, so
 * the session, the auth header and the tool calls behave as they will against a real server.
 *
 * <p>It is as strict as Kite's server (mcp-go): a body must be sent as exactly
 * {@code application/json}, and it answers protocol version 2025-03-26 whatever the client asks.
 */
public final class StubMcpServer implements AutoCloseable {

  private final HttpServer server;

  /** "tool {arguments}" for every tool call the server received. */
  public final List<String> calls = java.util.Collections.synchronizedList(new ArrayList<>());
  /** The Authorization header of every request, so tests see what Jizza sent. */
  public final List<String> authorizations = java.util.Collections.synchronizedList(new ArrayList<>());

  public StubMcpServer() {
    try {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    } catch (IOException e) {
      throw new IllegalStateException("Could not start the stub MCP server", e);
    }
    server.createContext("/mcp", this::handle);
    server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
    server.start();
  }

  public String url() {
    return "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
  }

  private void handle(HttpExchange exchange) throws IOException {
    String auth = exchange.getRequestHeaders().getFirst("Authorization");
    authorizations.add(auth == null ? "" : auth);
    if (!exchange.getRequestMethod().equals("POST")) {
      // No server-to-client stream and nothing to tear down.
      send(exchange, exchange.getRequestMethod().equals("DELETE") ? 200 : 405, null);
      return;
    }
    if (!"application/json".equals(exchange.getRequestHeaders().getFirst("Content-Type"))) {
      send(exchange, 400, "Invalid content type: must be 'application/json'");
      return;
    }
    JsonNode request = Json.read(new String(exchange.getRequestBody().readAllBytes(),
        StandardCharsets.UTF_8));
    if (!request.has("id")) {
      send(exchange, 202, null);
      return;
    }
    ObjectNode reply = Json.obj().put("jsonrpc", "2.0");
    reply.set("id", request.get("id"));
    ObjectNode result = reply.putObject("result");
    switch (request.path("method").asText()) {
      case "initialize" -> {
        result.put("protocolVersion", "2025-03-26");
        result.putObject("capabilities").putObject("tools");
        result.putObject("serverInfo").put("name", "stub-kite").put("version", "1.0");
        exchange.getResponseHeaders().add("Mcp-Session-Id", "session-1");
      }
      case "tools/list" -> {
        var tools = result.putArray("tools");
        tools.add(tool("login", "Log in to Kite. Returns a link to open.", Json.obj()));
        tools.add(tool("get_holdings", "Your holdings.", Json.obj()));
        ObjectNode order = Json.obj();
        order.putObject("symbol").put("type", "string");
        order.putObject("quantity").put("type", "integer");
        tools.add(tool("place_order", "Places an order.", order));
      }
      case "tools/call" -> {
        JsonNode params = request.path("params");
        calls.add(params.path("name").asText() + " " + params.path("arguments"));
        result.putArray("content").addObject().put("type", "text").put("text", "INFY x 10");
        result.putObject("structuredContent").putArray("holdings").addObject()
            .put("symbol", "INFY").put("quantity", 10);
      }
      default -> {
        reply.remove("result");
        reply.putObject("error").put("code", -32601).put("message", "Method not found");
      }
    }
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    send(exchange, 200, reply.toString());
  }

  private static ObjectNode tool(String name, String description, ObjectNode properties) {
    ObjectNode tool = Json.obj().put("name", name).put("description", description);
    tool.putObject("inputSchema").put("type", "object").set("properties", properties);
    return tool;
  }

  private static void send(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
    if (bytes.length > 0) exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
