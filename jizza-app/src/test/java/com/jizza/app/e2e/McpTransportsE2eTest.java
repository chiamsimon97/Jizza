package com.jizza.app.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.devicesim.SimDevice;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Older MCP servers (K7): one that speaks the HTTP+SSE transport, and a local (stdio) server the
 * person set up in the desktop app, reached through that app's own commands.
 */
@org.junit.jupiter.api.condition.EnabledIf(
    value = "com.jizza.engine.persistence.support.TestDatabase#available",
    disabledReason = "no PostgreSQL: set JIZZA_TEST_DB_URL or start Docker")
class McpTransportsE2eTest extends E2eSupport {

  private static final String PASSWORD = "Correct-Horse-92";

  @LocalServerPort private int port;

  private ApiClient api;
  private StubMcpServer stub;
  private SimDevice device;

  @BeforeEach
  void setUp() {
    stub = new StubMcpServer();
    api = new ApiClient(port);
    api.prime();
    String email = "mcp-transports-" + UUID.randomUUID() + "@jizza.local";
    api.post("/api/auth/register", Map.of("email", email, "password", PASSWORD));
    api.post("/api/auth/login", Map.of("email", email, "password", PASSWORD));
    api.post("/api/auth/step-up", Map.of("password", PASSWORD));
  }

  @AfterEach
  void tearDown() {
    stub.close();
    if (device != null) device.close();
  }

  @Test
  void anSseServerIsReachedOverTheOlderTransport() {
    Map<String, Object> body = new HashMap<>();
    body.put("name", "Old server");
    body.put("url", stub.sseUrl());
    body.put("transport", "sse");
    var created = api.post("/api/mcp-servers", body);
    assertThat(created.ok()).as(created.body()).isTrue();
    assertThat(created.json().path("status").asText()).as(created.body()).isEqualTo("connected");
    assertThat(created.json().path("transport").asText()).isEqualTo("sse");
    assertThat(created.json().path("tools").findValuesAsText("name"))
        .containsExactly("login", "get_holdings", "place_order");

    String id = created.json().path("id").asText();
    var called = api.post("/api/mcp-servers/" + id + "/tools/get_holdings/test", Map.of());
    assertThat(called.ok()).as(called.body()).isTrue();
    assertThat(called.body()).contains("INFY");
    assertThat(stub.calls).anyMatch(c -> c.startsWith("get_holdings"));

    var bad = new HashMap<>(body);
    bad.put("transport", "carrier-pigeon");
    assertThat(api.post("/api/mcp-servers", bad).status()).isEqualTo(400);
  }

  @Test
  void aLocalServerRunsThroughTheDesktopApp() {
    List<JsonNode> asked = new CopyOnWriteArrayList<>();
    device = new SimDevice("http://localhost:" + port);
    var items = device.json().createArrayNode();
    items.addObject().put("id", "files").put("name", "files");
    device.capability("system", null).capability("mcp", null).items("mcp_servers", items);
    device.handle("system.ping", input -> device.json().createObjectNode().put("pong", true));
    device.handle("mcp.listTools", input -> {
      asked.add(input);
      ObjectNode out = device.json().createObjectNode();
      ObjectNode tool = out.putArray("tools").addObject().put("name", "read_note")
          .put("description", "Reads a note.");
      tool.putObject("inputSchema").put("type", "object").putObject("properties")
          .putObject("title").put("type", "string");
      out.putObject("serverInfo").put("name", "notes-server").put("version", "0.3");
      return out;
    });
    device.handle("mcp.callTool", input -> {
      asked.add(input);
      ObjectNode out = device.json().createObjectNode().put("isError", false);
      out.putArray("content").addObject().put("type", "text")
          .put("text", "Note " + input.path("arguments").path("title").asText());
      return out;
    });
    var pairing = api.post("/api/devices/pairings", Map.of());
    device.enrollWithCode(pairing.json().path("code").asText(), "Work laptop", "windows");
    device.signIn();
    device.hello();
    device.sendManifest();
    device.connect();

    // The app says which servers it has, so the person picks one by name.
    var listed = api.get("/api/devices/" + device.deviceId());
    assertThat(listed.json().path("items").path("mcp_servers").get(0).path("name").asText())
        .isEqualTo("files");

    Map<String, Object> body = new HashMap<>();
    body.put("name", "Notes");
    body.put("transport", "device");
    body.put("deviceId", device.deviceId().toString());
    body.put("deviceServer", "files");
    var created = api.post("/api/mcp-servers", body);
    assertThat(created.ok()).as(created.body()).isTrue();
    assertThat(created.json().path("status").asText()).as(created.body()).isEqualTo("connected");
    assertThat(created.json().path("serverName").asText()).isEqualTo("notes-server");
    assertThat(created.json().path("tools").findValuesAsText("name")).containsExactly("read_note");
    assertThat(asked.get(0).path("server").asText()).isEqualTo("files");

    String id = created.json().path("id").asText();
    var called = api.post("/api/mcp-servers/" + id + "/tools/read_note/test",
        Map.of("title", "Groceries"));
    assertThat(called.ok()).as(called.body()).isTrue();
    assertThat(called.body()).contains("Note Groceries");
    assertThat(asked.get(asked.size() - 1).path("tool").asText()).isEqualTo("read_note");

    // The hub names the computer, not a made-up address.
    var cards = api.get("/api/connector-hub").json();
    JsonNode card = null;
    for (JsonNode c : cards) if (c.path("id").asText().equals(id)) card = c;
    assertThat(card).isNotNull();
    assertThat(card.path("detail").asText()).isEqualTo("On your computer · files");

    // It signs in on the computer, and it must be one of the person's own computers.
    var signed = new HashMap<>(body);
    signed.put("authType", "BEARER");
    signed.put("secret", "nope-nope-nope");
    assertThat(api.post("/api/mcp-servers", signed).status()).isEqualTo(400);
    var stranger = new HashMap<>(body);
    stranger.put("deviceId", UUID.randomUUID().toString());
    assertThat(api.post("/api/mcp-servers", stranger).status()).isEqualTo(404);
  }
}
