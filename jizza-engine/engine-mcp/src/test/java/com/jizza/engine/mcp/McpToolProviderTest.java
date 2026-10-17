package com.jizza.engine.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jizza.engine.api.enums.ActionType;
import com.jizza.engine.api.tool.ToolCall;
import com.jizza.engine.api.tool.ToolResult;
import com.jizza.engine.api.tool.ToolSpec;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.persistence.entity.AgentEntity;
import com.jizza.engine.persistence.repository.AgentRepository;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class McpToolProviderTest {

  private final UUID kaiId = UUID.randomUUID();
  private final UUID vitaId = UUID.randomUUID();
  private McpProperties props;
  private FakeSession session;
  private int opened;
  private MutableClock clock;
  private McpToolProvider provider;

  /** A server in memory: its tools, what it was asked, and whether it is up. */
  static final class FakeSession implements McpSession {
    List<McpSchema.Tool> tools = new ArrayList<>();
    final List<String> calls = new ArrayList<>();
    boolean down;
    McpSchema.CallToolResult next = McpSchema.CallToolResult.builder()
        .content(List.of(new McpSchema.TextContent("ok"))).build();

    @Override public List<McpSchema.Tool> listTools() {
      if (down) throw new IllegalStateException("connection refused");
      return tools;
    }

    @Override public McpSchema.CallToolResult callTool(String name, Map<String, Object> arguments) {
      if (down) throw new IllegalStateException("connection refused");
      calls.add(name + " " + arguments);
      return next;
    }

    @Override public void close() { }
  }

  static final class MutableClock extends Clock {
    Instant now = Instant.parse("2026-10-17T10:00:00Z");
    @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(java.time.ZoneId zone) { return this; }
    @Override public Instant instant() { return now; }
  }

  @BeforeEach
  void setUp() {
    AgentRepository agents = mock(AgentRepository.class);
    when(agents.findById(kaiId)).thenReturn(Optional.of(agent(kaiId, "finance")));
    when(agents.findById(vitaId)).thenReturn(Optional.of(agent(vitaId, "health")));

    props = new McpProperties();
    McpProperties.Server notes = new McpProperties.Server();
    notes.setAgents(List.of("finance"));
    notes.getActionTypes().put("search_notes", ActionType.READ);
    props.getServers().put("notes", notes);

    session = new FakeSession();
    session.tools.add(tool("search_notes", "Search the notes"));
    session.tools.add(tool("delete_note", "Delete a note"));
    clock = new MutableClock();
    provider = new McpToolProvider(props, (name, server) -> {
      opened++;
      return session;
    }, agents, clock);
  }

  private static AgentEntity agent(UUID id, String key) {
    AgentEntity agent = new AgentEntity();
    agent.setId(id);
    agent.setKey(key);
    return agent;
  }

  private static McpSchema.Tool tool(String name, String description) {
    return McpSchema.Tool.builder().name(name).description(description)
        .inputSchema(Map.of("type", "object", "properties", Map.of())).build();
  }

  @Test
  void onlyTheAgentsTheOwnerNamedSeeAServer() {
    assertThat(provider.toolsFor(kaiId)).extracting(ToolSpec::name)
        .containsExactlyInAnyOrder("mcp_notes__search_notes", "mcp_notes__delete_note");
    assertThat(provider.toolsFor(vitaId)).isEmpty();
    assertThat(provider.supports(vitaId, "mcp_notes__search_notes")).isFalse();
  }

  @Test
  void aServerWithNoAgentsNamedIsOfferedToNobody() {
    props.getServers().get("notes").setAgents(List.of());
    assertThat(provider.toolsFor(kaiId)).isEmpty();
    assertThat(opened).isZero();
  }

  @Test
  void theToolAllowListNarrowsWhatIsOffered() {
    props.getServers().get("notes").setTools(List.of("search_notes"));
    assertThat(provider.toolsFor(kaiId)).extracting(ToolSpec::name)
        .containsExactly("mcp_notes__search_notes");
    assertThat(provider.supports(kaiId, "mcp_notes__delete_note")).isFalse();
  }

  @Test
  void whatAToolDoesComesFromTheOwnersConfigurationAndAnythingElseIsSideEffecting() {
    assertThat(provider.actionTypeOf(kaiId, "mcp_notes__search_notes")).contains(ActionType.READ);
    assertThat(provider.actionTypeOf(kaiId, "mcp_notes__delete_note")).isEmpty();
    assertThat(provider.actionTypeOf(kaiId, "gmail_send_message")).isEmpty();

    var specs = provider.toolsFor(kaiId);
    assertThat(specs).filteredOn(s -> s.name().endsWith("search_notes"))
        .allSatisfy(s -> assertThat(s.sideEffecting()).isFalse());
    assertThat(specs).filteredOn(s -> s.name().endsWith("delete_note"))
        .allSatisfy(s -> assertThat(s.sideEffecting()).isTrue());
  }

  @Test
  void aServerDescriptionIsLabelledShortenedAndScrubbed() {
    session.tools.set(0, tool("search_notes", "Bearer abcdefghijklmnop " + "x".repeat(3000)));
    ToolSpec spec = provider.toolsFor(kaiId).get(0);
    assertThat(spec.description()).startsWith("[notes via MCP] ").contains("[redacted]")
        .hasSizeLessThan(1100);
  }

  @Test
  void aCallGoesToTheServerUnderTheToolsOwnName() {
    ToolResult result = provider.invoke(kaiId, new ToolCall("tu_1", "mcp_notes__search_notes",
        Json.obj().put("query", "tax")));

    assertThat(result.ok()).isTrue();
    assertThat(session.calls).containsExactly("search_notes {query=tax}");
  }

  @Test
  void anAgentCannotCallAServerItWasNotGiven() {
    ToolResult result = provider.invoke(vitaId, new ToolCall("tu_1", "mcp_notes__search_notes",
        Json.obj()));
    assertThat(result.ok()).isFalse();
    assertThat(session.calls).isEmpty();
  }

  @Test
  void anUnreachableServerIsLeftOutAndTriedAgainLater() {
    session.down = true;
    assertThat(provider.toolsFor(kaiId)).isEmpty();

    session.down = false;
    assertThat(provider.toolsFor(kaiId)).isEmpty(); // still within the minute

    clock.now = clock.now.plus(Duration.ofMinutes(2));
    assertThat(provider.toolsFor(kaiId)).hasSize(2);
  }

  @Test
  void theToolListIsReusedUntilItExpires() {
    provider.toolsFor(kaiId);
    session.tools.add(tool("archive_note", "Archive"));
    assertThat(provider.toolsFor(kaiId)).hasSize(2);

    clock.now = clock.now.plus(Duration.ofMinutes(6));
    assertThat(provider.toolsFor(kaiId)).hasSize(3);
  }

  @Test
  void resultsBecomeToolResults() {
    assertThat(McpToolProvider.toResult(McpSchema.CallToolResult.builder()
        .content(List.of(new McpSchema.TextContent("no such note"))).isError(true).build()))
        .satisfies(r -> {
          assertThat(r.ok()).isFalse();
          assertThat(r.error()).isEqualTo("no such note");
        });
    assertThat(McpToolProvider.toResult(McpSchema.CallToolResult.builder()
        .content(List.of(new McpSchema.TextContent("{}")))
        .structuredContent(Map.of("count", 2)).build()).output().path("count").asInt())
        .isEqualTo(2);
  }

  @Test
  void namesFitTheProvidersRules() {
    assertThat(McpToolProvider.toolName("my docs", "find.page")).isEqualTo("mcp_my_docs__find_page");
    String longName = McpToolProvider.toolName("server", "t".repeat(100));
    assertThat(longName).hasSize(64).matches("[A-Za-z0-9_-]+");
    assertThat(McpToolProvider.toolName("server", "t".repeat(100)))
        .isNotEqualTo(McpToolProvider.toolName("server", "t".repeat(99) + "u"));
  }

  @Test
  void argumentsCanBeKeptOutOfTheAuditLogPerServer() {
    assertThat(provider.redactArguments("mcp_notes__search_notes")).isFalse();
    props.getServers().get("notes").setRedactArguments(true);
    assertThat(provider.redactArguments("mcp_notes__search_notes")).isTrue();
    assertThat(provider.redactArguments("knowledge_search")).isFalse();
  }
}
