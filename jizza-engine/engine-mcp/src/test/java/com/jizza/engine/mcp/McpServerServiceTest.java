package com.jizza.engine.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jizza.engine.api.crypto.EncryptionService;
import com.jizza.engine.api.enums.ActionType;
import com.jizza.engine.api.mcp.McpAuthType;
import com.jizza.engine.api.mcp.McpServerDto;
import com.jizza.engine.api.mcp.McpServerSettings;
import com.jizza.engine.api.tool.ToolCall;
import com.jizza.engine.api.tool.ToolSpec;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.persistence.entity.AgentEntity;
import com.jizza.engine.persistence.entity.McpServerEntity;
import com.jizza.engine.persistence.repository.AgentRepository;
import com.jizza.engine.persistence.repository.McpServerRepository;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A server added in the app behaves like a configured one: its tools reach the agents the user
 * named, with what the user decided about each tool, and nothing else.
 */
class McpServerServiceTest {

  private final UUID userId = UUID.randomUUID();
  private final UUID workspaceId = UUID.randomUUID();
  private final UUID jizzaId = UUID.randomUUID();
  private final UUID kaiId = UUID.randomUUID();
  private final Map<UUID, McpServerEntity> rows = new LinkedHashMap<>();
  private final List<McpProperties.Server> opened = new ArrayList<>();
  private McpToolProviderTest.FakeSession kite;
  private McpOAuth oauth;
  private McpToolProvider provider;
  private McpServerService service;

  @BeforeEach
  void setUp() {
    AgentRepository agents = mock(AgentRepository.class);
    when(agents.findById(jizzaId)).thenReturn(Optional.of(agent(jizzaId, "jizza")));
    when(agents.findById(kaiId)).thenReturn(Optional.of(agent(kaiId, "finance")));

    kite = new McpToolProviderTest.FakeSession();
    for (String name : List.of("login", "get_holdings", "place_order")) {
      kite.tools.add(McpSchema.Tool.builder().name(name).description("Kite " + name)
          .inputSchema(Map.of("type", "object", "properties", Map.of())).build());
    }
    provider = new McpToolProvider(new McpProperties(), (name, server) -> {
      opened.add(server);
      return kite;
    }, agents);
    oauth = mock(McpOAuth.class);
    service = new McpServerService(repository(), provider, new FakeEncryption(), oauth);
  }

  @Test
  void anAddedServersToolsReachTheAgentsItIsGivenTo() {
    McpServerDto kiteServer = service.create(userId, workspaceId, settings("Zerodha Kite",
        McpAuthType.NONE, null, null));
    McpServerDto listed = service.refreshTools(kiteServer.id());

    assertThat(listed.status()).isEqualTo("connected");
    assertThat(listed.slug()).isEqualTo("zerodha_kite");
    assertThat(listed.tools()).extracting(t -> t.toolName()).containsExactly(
        "mcp_zerodha_kite__login", "mcp_zerodha_kite__get_holdings",
        "mcp_zerodha_kite__place_order");
    // New servers go to Jizza, and only to Jizza until the user says otherwise.
    assertThat(provider.toolsFor(jizzaId)).extracting(ToolSpec::name)
        .contains("mcp_zerodha_kite__get_holdings");
    assertThat(provider.toolsFor(kaiId)).isEmpty();
    assertThat(provider.workspaceOf(jizzaId, "mcp_zerodha_kite__get_holdings"))
        .contains(workspaceId);

    var result = provider.invoke(jizzaId, new ToolCall("1", "mcp_zerodha_kite__get_holdings",
        Json.obj()));
    assertThat(result.ok()).isTrue();
    assertThat(kite.calls).containsExactly("get_holdings {}");
  }

  @Test
  void theUserDecidesWhichToolsAreOnAndWhatTheyDo() {
    UUID id = service.create(userId, workspaceId, settings("Kite", McpAuthType.NONE, null, null)).id();
    service.refreshTools(id);

    // What each tool does is suggested from its name, and the user can change it.
    McpServerDto listed = service.find(id).orElseThrow();
    assertThat(listed.tools()).extracting(t -> t.actionType()).containsExactly(ActionType.READ,
        ActionType.READ, ActionType.MONEY);
    assertThat(listed.tools()).allMatch(t -> t.suggested());
    service.updateTool(id, "get_holdings", null, ActionType.UPDATE);
    assertThat(provider.actionTypeOf(jizzaId, "mcp_kite__get_holdings")).contains(ActionType.UPDATE);
    service.updateTool(id, "get_holdings", null, ActionType.READ);
    assertThat(service.find(id).orElseThrow().tools().get(1).suggested()).isFalse();
    service.updateTool(id, "place_order", false, null);

    assertThat(provider.actionTypeOf(jizzaId, "mcp_kite__get_holdings")).contains(ActionType.READ);
    assertThat(provider.toolsFor(jizzaId)).extracting(ToolSpec::name)
        .containsExactly("mcp_kite__login", "mcp_kite__get_holdings");
    assertThat(provider.toolsFor(jizzaId).stream()
        .filter(t -> t.name().equals("mcp_kite__get_holdings")).findFirst().orElseThrow()
        .sideEffecting()).isFalse();
    assertThat(provider.supports(jizzaId, "mcp_kite__place_order")).isFalse();
  }

  @Test
  void aWrongEarlierSuggestionIsCorrectedButTheUsersChoiceIsKept() {
    UUID id = service.create(userId, workspaceId, settings("Kite", McpAuthType.NONE, null, null)).id();
    service.refreshTools(id);
    // As an older classifier left it: reads marked as deleting, one of them the user's own choice.
    McpServerEntity row = rows.get(id);
    row.setToolSettings(Json.read("""
        {"get_holdings":{"actionType":"delete","suggested":true},
         "login":{"actionType":"update"}}"""));

    assertThat(service.resuggestAll()).isEqualTo(1);

    var tools = service.find(id).orElseThrow().tools();
    assertThat(tools.get(1).actionType()).isEqualTo(ActionType.READ);
    assertThat(tools.get(1).suggested()).isTrue();
    assertThat(tools.get(0).actionType()).isEqualTo(ActionType.UPDATE);
    assertThat(tools.get(0).suggested()).isFalse();
    assertThat(service.resuggestAll()).isZero();
  }

  @Test
  void aToolLearnsItsOutputFieldsFromARun() {
    UUID id = service.create(userId, workspaceId, settings("Kite", McpAuthType.NONE, null, null)).id();
    service.refreshTools(id);
    assertThat(service.find(id).orElseThrow().tools().get(1).outputSchema()).isNull();

    service.learnOutput(id, "get_holdings", Json.read("[{\"tradingsymbol\":\"INFY\",\"quantity\":10}]"));

    var tool = service.find(id).orElseThrow().tools().get(1);
    assertThat(tool.outputSource()).isEqualTo("learnt");
    assertThat(tool.outputSchema().path("items").path("properties").has("tradingsymbol")).isTrue();
    assertThat(tool.outputSchema().toString()).doesNotContain("INFY");
    // Text teaches nothing.
    service.learnOutput(id, "login", Json.mapper().getNodeFactory().textNode("open this link"));
    assertThat(service.find(id).orElseThrow().tools().get(0).outputSchema()).isNull();
  }

  @Test
  void aTokenIsSentAsTheUserChoseAndNeverShown() {
    UUID id = service.create(userId, workspaceId, settings("Docs", McpAuthType.BEARER, null,
        "secret-token")).id();
    McpServerDto dto = service.refreshTools(id);

    assertThat(dto.secretPresent()).isTrue();
    assertThat(dto.toString()).doesNotContain("secret-token");
    assertThat(opened.getLast().getHeaders()).containsEntry("Authorization", "Bearer secret-token");

    service.update(id, new McpServerSettings(null, null, McpAuthType.HEADER, "X-Api-Key", "k-2",
        null, List.of("jizza", "finance"), null, null, null, null));
    service.refreshTools(id);
    assertThat(opened.getLast().getHeaders()).containsEntry("X-Api-Key", "k-2")
        .doesNotContainKey("Authorization");
    assertThat(provider.toolsFor(kaiId)).isNotEmpty();
  }

  @Test
  void anOAuthServerIsNotOfferedUntilSignedIn() {
    UUID id = service.create(userId, workspaceId, settings("Notion", McpAuthType.OAUTH, null, null)).id();
    assertThat(service.refreshTools(id).status()).isEqualTo("needs_auth");
    assertThat(provider.toolsFor(jizzaId)).isEmpty();
    assertThat(service.call(id, "search", Json.obj()).ok()).isFalse();
    assertThat(opened).isEmpty();
  }

  @Test
  void anUnreachableServerIsReportedNotThrown() {
    UUID id = service.create(userId, workspaceId, settings("Down", McpAuthType.NONE, null, null)).id();
    kite.down = true;
    McpServerDto dto = service.refreshTools(id);
    assertThat(dto.status()).isEqualTo("error");
    assertThat(dto.lastError()).contains("Could not reach");
  }

  @Test
  void slugsStayUniquePerPerson() {
    service.create(userId, workspaceId, settings("Kite", McpAuthType.NONE, null, null));
    assertThat(service.create(userId, workspaceId, settings("Kite", McpAuthType.NONE, null, null))
        .slug()).isEqualTo("kite_2");
  }

  // ── Fixtures ──────────────────────────────────────────────────────────────────

  private static McpServerSettings settings(String name, McpAuthType auth, String header,
                                            String secret) {
    return new McpServerSettings(name, "https://mcp.example.com/mcp", auth, header, secret, null,
        null, null, null, null, null);
  }

  private AgentEntity agent(UUID id, String key) {
    AgentEntity agent = new AgentEntity();
    agent.setId(id);
    agent.setUserId(userId);
    agent.setKey(key);
    agent.setName(key);
    agent.setEnabled(true);
    return agent;
  }

  private McpServerRepository repository() {
    McpServerRepository repo = mock(McpServerRepository.class);
    when(repo.save(any())).thenAnswer(inv -> {
      McpServerEntity e = inv.getArgument(0);
      if (e.getId() == null) e.setId(UUID.randomUUID());
      rows.put(e.getId(), e);
      return e;
    });
    when(repo.findAll()).thenAnswer(inv -> new ArrayList<>(rows.values()));
    when(repo.findById(any())).thenAnswer(inv -> Optional.ofNullable(rows.get(inv.<UUID>getArgument(0))));
    when(repo.findByUserIdOrderByNameAsc(any())).thenAnswer(inv -> rows.values().stream()
        .filter(e -> e.getUserId().equals(inv.getArgument(0))).toList());
    when(repo.findByWorkspaceIdOrderByNameAsc(any())).thenAnswer(inv -> rows.values().stream()
        .filter(e -> e.getWorkspaceId().equals(inv.getArgument(0))).toList());
    when(repo.existsByUserIdAndSlug(any(), any())).thenAnswer(inv -> rows.values().stream()
        .anyMatch(e -> e.getUserId().equals(inv.getArgument(0))
            && e.getSlug().equals(inv.getArgument(1))));
    return repo;
  }

  /** Reversible and obviously not plaintext, which is all these tests need. */
  static final class FakeEncryption implements EncryptionService {
    @Override public byte[] encrypt(byte[] plain) {
      byte[] out = plain.clone();
      for (int i = 0; i < out.length; i++) out[i] ^= 0x5A;
      return out;
    }

    @Override public byte[] decrypt(byte[] ciphertext) { return encrypt(ciphertext); }
  }
}
