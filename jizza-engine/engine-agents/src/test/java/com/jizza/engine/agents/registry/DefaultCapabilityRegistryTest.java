package com.jizza.engine.agents.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jizza.engine.agents.support.AgentTestFixtures;
import com.jizza.engine.agents.support.AgentTestFixtures.FixedGate;
import com.jizza.engine.agents.support.AgentTestFixtures.RecordingProvider;
import com.jizza.engine.agents.support.AgentTestFixtures.TogglableKillSwitch;
import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.dto.NotificationDto;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.enums.AutonomyRule;
import com.jizza.engine.api.enums.NotificationKind;
import com.jizza.engine.api.notification.NotificationBus;
import com.jizza.engine.api.tool.JsonSchemas;
import com.jizza.engine.api.tool.ToolCall;
import com.jizza.engine.api.tool.ToolProvider;
import com.jizza.engine.api.tool.ToolSpec;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.persistence.entity.AgentEntity;
import com.jizza.engine.persistence.repository.AgentCapabilityRepository;
import com.jizza.engine.persistence.repository.AgentRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class DefaultCapabilityRegistryTest {

  private static final ToolSpec SEND_EMAIL = new ToolSpec("send_email", "Send an email",
      JsonSchemas.object(Map.of("to", JsonSchemas.string("recipient")), List.of("to")), true);

  private AgentEntity agent;
  private RecordingProvider provider;
  private FixedGate gate;
  private TogglableKillSwitch killSwitch;
  private AuditLog audit;
  private NotificationBus bus;
  private DefaultCapabilityRegistry registry;

  @BeforeEach
  void setUp() {
    agent = AgentTestFixtures.agent("professional", "Cato", false);
    provider = new RecordingProvider(SEND_EMAIL);
    gate = new FixedGate();
    killSwitch = new TogglableKillSwitch();
    audit = mock(AuditLog.class);
    bus = mock(NotificationBus.class);

    AgentRepository agents = mock(AgentRepository.class);
    when(agents.findById(agent.getId())).thenReturn(Optional.of(agent));

    registry = new DefaultCapabilityRegistry(providers(provider), agents,
        mock(AgentCapabilityRepository.class), gate, killSwitch, audit, busProvider(bus));
  }

  private ToolCall validCall() {
    return new ToolCall("call-1", "send_email", Json.obj().put("to", "someone@example.com"));
  }

  @Test
  void invokesAnAllowedToolAndAuditsBothSides() {
    var result = registry.invoke(agent.getId(), validCall());

    assertThat(result.ok()).isTrue();
    assertThat(provider.invocations).hasSize(1);
    verify(audit).record(eq(agent.getUserId()), eq(ActorType.AGENT), eq(agent.getId()),
        eq("tool.call"), eq("send_email"), any());
    verify(audit).record(eq(agent.getUserId()), eq(ActorType.AGENT), eq(agent.getId()),
        eq("tool.result.ok"), eq("send_email"), any());
  }

  @Test
  void aCallInAConversationIsKeptSoTheJobCanBeSavedAsSteps() {
    ServiceCallJournal journal = new ServiceCallJournal();
    registry.setJournal(journal);
    UUID conversationId = UUID.randomUUID();
    var sink = new com.jizza.engine.api.activity.ActivitySink() {
      @Override public void emit(com.jizza.engine.api.activity.ActivityEvent event) {}
      @Override public boolean cancelled() { return false; }
      @Override public UUID conversationId() { return conversationId; }
    };

    com.jizza.engine.api.activity.Activity.run(sink, () -> registry.invoke(agent.getId(), validCall()));
    gate.rule = AutonomyRule.DENY;
    com.jizza.engine.api.activity.Activity.run(sink, () -> registry.invoke(agent.getId(), validCall()));

    assertThat(journal.recent(conversationId)).singleElement().satisfies(c -> {
      assertThat(c.tool()).isEqualTo("send_email");
      assertThat(c.arguments()).isEqualTo(validCall().args());
    });
  }

  @Test
  void aDeniedToolIsBlockedBeforeItRuns() {
    gate.rule = AutonomyRule.DENY;

    var result = registry.invoke(agent.getId(), validCall());

    assertThat(result.ok()).isFalse();
    assertThat(provider.invocations).as("the tool must never execute").isEmpty();
    verify(audit).record(any(), any(), any(), eq("tool.blocked"), eq("send_email"), any());
  }

  @Test
  void aDeniedToolIsNotEvenOfferedToTheModel() {
    assertThat(registry.toolsFor(agent.getId())).extracting(ToolSpec::name).contains("send_email");
    gate.rule = AutonomyRule.DENY;
    assertThat(registry.toolsFor(agent.getId())).isEmpty();
  }

  @Test
  void anAskRuleRaisesAPermissionRequestInsteadOfRunning() {
    gate.rule = AutonomyRule.ASK;
    UUID notificationId = UUID.randomUUID();
    when(bus.raise(eq(NotificationKind.PERMISSION_REQUEST), eq(agent.getId()), any()))
        .thenReturn(notification(notificationId));

    var result = registry.invoke(agent.getId(), validCall());

    assertThat(provider.invocations).isEmpty();
    assertThat(result.ok()).isFalse();
    assertThat(result.error())
        .isEqualTo(DefaultCapabilityRegistry.PERMISSION_REQUIRED + notificationId);
    verify(audit).record(any(), any(), any(), eq("tool.permission_requested"), eq("send_email"), any());
  }

  @Test
  void argumentsAreValidatedAgainstTheSchemaBeforeExecution() {
    var badCall = new ToolCall("call-2", "send_email", Json.obj().put("recipient", "oops"));

    var result = registry.invoke(agent.getId(), badCall);

    assertThat(result.ok()).isFalse();
    assertThat(result.error()).contains("did not match the tool schema");
    assertThat(provider.invocations).isEmpty();
  }

  @Test
  void aToolTheAgentDoesNotHoldIsRefused() {
    var result = registry.invoke(agent.getId(),
        new ToolCall("call-3", "transfer_money", Json.obj()));

    assertThat(result.ok()).isFalse();
    assertThat(result.error()).contains("not assigned to this agent");
  }

  @Test
  void theKillSwitchStopsAutonomousCallsButNotAttendedOnes() {
    killSwitch.paused = true;

    var autonomous = registry.invoke(agent.getId(), validCall(), true);
    assertThat(autonomous.ok()).isFalse();
    assertThat(autonomous.error()).contains("kill switch");
    assertThat(provider.invocations).isEmpty();

    var attended = registry.invoke(agent.getId(), validCall(), false);
    assertThat(attended.ok()).isTrue();
  }

  @Test
  void aDisabledAgentCannotInvokeAnything() {
    agent.setEnabled(false);
    var result = registry.invoke(agent.getId(), validCall());
    assertThat(result.ok()).isFalse();
    assertThat(result.error()).contains("disabled");
    assertThat(provider.invocations).isEmpty();
  }

  @Test
  void aThrowingToolBecomesAFailedResultRatherThanAnEscapingException() {
    RecordingProvider throwing = new RecordingProvider(SEND_EMAIL) {
      @Override
      public com.jizza.engine.api.tool.ToolResult invoke(UUID agentId, ToolCall call) {
        throw new IllegalStateException("connector exploded");
      }
    };
    AgentRepository agents = mock(AgentRepository.class);
    when(agents.findById(agent.getId())).thenReturn(Optional.of(agent));
    var isolated = new DefaultCapabilityRegistry(providers(throwing), agents,
        mock(AgentCapabilityRepository.class), gate, killSwitch, audit, busProvider(bus));

    var result = isolated.invoke(agent.getId(), validCall());
    assertThat(result.ok()).isFalse();
    assertThat(result.error()).contains("connector exploded");
  }

  private NotificationDto notification(UUID id) {
    return new NotificationDto(id, agent.getUserId(), agent.getId(), agent.getName(),
        NotificationKind.PERMISSION_REQUEST, "needs approval", null, null, null, null,
        com.jizza.engine.api.enums.NotificationStatus.UNREAD, null, null,
        com.jizza.engine.api.enums.EscalationState.NONE, java.time.Instant.now(), null);
  }

  @SuppressWarnings("unchecked")
  private ObjectProvider<ToolProvider> providers(ToolProvider... list) {
    ObjectProvider<ToolProvider> op = mock(ObjectProvider.class);
    when(op.stream()).thenAnswer(inv -> java.util.Arrays.stream(list));
    when(op.iterator()).thenAnswer(inv -> java.util.Arrays.asList(list).iterator());
    org.mockito.Mockito.doAnswer(inv -> {
      java.util.function.Consumer<ToolProvider> consumer = inv.getArgument(0);
      for (ToolProvider p : list) consumer.accept(p);
      return null;
    }).when(op).forEach(any());
    return op;
  }

  @SuppressWarnings("unchecked")
  private ObjectProvider<NotificationBus> busProvider(NotificationBus value) {
    ObjectProvider<NotificationBus> op = mock(ObjectProvider.class);
    when(op.getIfAvailable()).thenReturn(value);
    return op;
  }
}
