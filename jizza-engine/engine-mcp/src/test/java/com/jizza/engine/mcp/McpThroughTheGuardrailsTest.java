package com.jizza.engine.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jizza.engine.agents.registry.DefaultCapabilityRegistry;
import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.autonomy.ActionTypeSource;
import com.jizza.engine.api.autonomy.KillSwitch;
import com.jizza.engine.api.enums.ActionType;
import com.jizza.engine.api.notification.NotificationBus;
import com.jizza.engine.api.tool.ToolCall;
import com.jizza.engine.api.tool.ToolProvider;
import com.jizza.engine.api.tool.ToolResult;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.autonomy.policy.PolicyAutonomyGate;
import com.jizza.engine.persistence.entity.AgentEntity;
import com.jizza.engine.persistence.repository.AgentCapabilityRepository;
import com.jizza.engine.persistence.repository.AgentRepository;
import com.jizza.engine.persistence.repository.AutonomyPolicyRepository;
import com.jizza.engine.persistence.repository.GeneratedToolRepository;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * An MCP tool goes through the same Capability Registry and the same guardrails as every other
 * tool: what the owner classified as reading runs, anything else waits for approval, and a paused
 * kill switch stops it when the agent works unattended.
 */
class McpThroughTheGuardrailsTest {

  private final UUID userId = UUID.randomUUID();
  private AgentEntity kai;
  private McpToolProviderTest.FakeSession server;
  private AuditLog audit;
  private KillSwitch killSwitch;
  private DefaultCapabilityRegistry registry;

  @BeforeEach
  void setUp() {
    kai = new AgentEntity();
    kai.setId(UUID.randomUUID());
    kai.setUserId(userId);
    kai.setKey("finance");
    kai.setName("Kai");
    kai.setEnabled(true);
    AgentRepository agents = mock(AgentRepository.class);
    when(agents.findById(kai.getId())).thenReturn(Optional.of(kai));

    McpProperties props = new McpProperties();
    McpProperties.Server notes = new McpProperties.Server();
    notes.setAgents(List.of("finance"));
    notes.getActionTypes().put("search_notes", ActionType.READ);
    props.getServers().put("notes", notes);

    server = new McpToolProviderTest.FakeSession();
    for (String name : List.of("search_notes", "delete_note")) {
      server.tools.add(McpSchema.Tool.builder().name(name).description(name)
          .inputSchema(Map.of("type", "object", "properties", Map.of())).build());
    }
    McpToolProvider mcp = new McpToolProvider(props, (n, s) -> server, agents);

    PolicyAutonomyGate gate = new PolicyAutonomyGate(mock(AutonomyPolicyRepository.class), agents,
        mock(GeneratedToolRepository.class));
    StaticListableBeanFactory sources = new StaticListableBeanFactory();
    sources.addBean("mcp", mcp);
    gate.setActionTypeSources(sources.getBeanProvider(ActionTypeSource.class));

    StaticListableBeanFactory providers = new StaticListableBeanFactory();
    providers.addBean("mcp", mcp);
    audit = mock(AuditLog.class);
    killSwitch = mock(KillSwitch.class);
    registry = new DefaultCapabilityRegistry(providers.getBeanProvider(ToolProvider.class), agents,
        mock(AgentCapabilityRepository.class), gate, killSwitch, audit,
        new StaticListableBeanFactory().getBeanProvider(NotificationBus.class));
  }

  @Test
  void aToolTheOwnerClassifiedAsReadingRuns() {
    ToolResult result = registry.invoke(kai.getId(),
        new ToolCall("tu_1", "mcp_notes__search_notes", Json.obj()));

    assertThat(result.ok()).isTrue();
    assertThat(server.calls).containsExactly("search_notes {}");
    verify(audit).record(eq(userId), any(), eq(kai.getId()), eq("tool.call"),
        eq("mcp_notes__search_notes"), any());
  }

  @Test
  void aToolTheOwnerDidNotClassifyWaitsForApprovalAndNeverReachesTheServer() {
    ToolResult result = registry.invoke(kai.getId(),
        new ToolCall("tu_2", "mcp_notes__delete_note", Json.obj()));

    assertThat(result.ok()).isFalse();
    assertThat(result.error()).contains("needs your approval");
    assertThat(server.calls).isEmpty();
    verify(audit).record(eq(userId), any(), eq(kai.getId()), eq("tool.permission_requested"),
        eq("mcp_notes__delete_note"), any());
  }

  @Test
  void theKillSwitchStopsEvenAReadingToolWhenTheAgentWorksUnattended() {
    when(killSwitch.paused(userId)).thenReturn(true);

    ToolResult result = registry.invoke(kai.getId(),
        new ToolCall("tu_3", "mcp_notes__search_notes", Json.obj()), true);

    assertThat(result.ok()).isFalse();
    assertThat(server.calls).isEmpty();
  }

  @Test
  void theModelIsOfferedTheToolsButNeverTheServersConnectionDetails() {
    var specs = registry.toolsFor(kai.getId());

    assertThat(specs).extracting(s -> s.name())
        .containsExactlyInAnyOrder("mcp_notes__search_notes", "mcp_notes__delete_note");
    assertThat(Json.write(specs)).doesNotContain("command").doesNotContain("headers");
  }
}
