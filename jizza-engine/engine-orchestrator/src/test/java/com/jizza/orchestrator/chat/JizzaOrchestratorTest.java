package com.jizza.orchestrator.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jizza.common.agent.Agent;
import com.jizza.common.agent.AgentRequest;
import com.jizza.common.agent.AgentResult;
import com.jizza.common.audit.AuditLog;
import com.jizza.common.enums.MessageRole;
import com.jizza.common.error.NotFoundException;
import com.jizza.common.llm.Msg;
import com.jizza.common.orchestrator.AssistantReply;
import com.jizza.common.realtime.RealtimeGateway;
import com.jizza.orchestrator.delegation.DelegationRecorder;
import com.jizza.persistence.entity.AgentEntity;
import com.jizza.persistence.entity.ConversationEntity;
import com.jizza.persistence.entity.DelegationTraceEntity;
import com.jizza.persistence.entity.MessageEntity;
import com.jizza.persistence.repository.AgentRepository;
import com.jizza.persistence.repository.ConversationRepository;
import com.jizza.persistence.repository.DelegationTraceRepository;
import com.jizza.persistence.repository.MessageRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class JizzaOrchestratorTest {

  private final UUID userId = UUID.randomUUID();
  private final List<MessageEntity> messageRows = new ArrayList<>();
  private final List<ConversationEntity> conversationRows = new ArrayList<>();
  private final List<DelegationTraceEntity> traceRows = new ArrayList<>();

  private AgentEntity jizza;
  private Agent baseAgent;
  private DelegationRecorder recorder;
  private JizzaOrchestrator orchestrator;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    jizza = new AgentEntity();
    jizza.setId(UUID.randomUUID());
    jizza.setUserId(userId);
    jizza.setKey("jizza");
    jizza.setName("Jizza");
    jizza.setSystemPrompt("prompt");
    jizza.setOrchestrator(true);
    jizza.setEnabled(true);

    AgentRepository agents = mock(AgentRepository.class);
    when(agents.findByUserIdAndIsOrchestratorTrue(userId)).thenReturn(Optional.of(jizza));

    ConversationRepository conversations = mock(ConversationRepository.class);
    when(conversations.save(any())).thenAnswer(inv -> {
      ConversationEntity c = inv.getArgument(0);
      if (c.getId() == null) c.setId(UUID.randomUUID());
      c.setCreatedAt(Instant.now());
      conversationRows.add(c);
      return c;
    });
    when(conversations.findByUserIdOrderByCreatedAtDesc(eq(userId), any()))
        .thenAnswer(inv -> List.copyOf(conversationRows));
    when(conversations.findById(any())).thenAnswer(inv ->
        conversationRows.stream().filter(c -> c.getId().equals(inv.getArgument(0))).findFirst());

    MessageRepository messages = mock(MessageRepository.class);
    when(messages.save(any())).thenAnswer(inv -> {
      MessageEntity m = inv.getArgument(0);
      m.setId(UUID.randomUUID());
      m.setCreatedAt(Instant.now().plusNanos(messageRows.size()));
      messageRows.add(m);
      return m;
    });
    when(messages.findByConversationIdOrderByCreatedAtDesc(any(), any())).thenAnswer(inv ->
        messageRows.stream()
            .filter(m -> m.getConversationId().equals(inv.<UUID>getArgument(0)))
            .sorted(Comparator.comparing(MessageEntity::getCreatedAt).reversed())
            .toList());

    DelegationTraceRepository traces = mock(DelegationTraceRepository.class);
    when(traces.save(any())).thenAnswer(inv -> {
      traceRows.add(inv.getArgument(0));
      return inv.getArgument(0);
    });

    baseAgent = mock(Agent.class);
    recorder = new DelegationRecorder();

    ObjectProvider<RealtimeGateway> realtime = mock(ObjectProvider.class);
    orchestrator = new JizzaOrchestrator(agents, conversations, messages, traces, baseAgent,
        recorder, mock(AuditLog.class), realtime);
  }

  private void answerWith(String text, DelegationRecorder.Contribution... contributions) {
    when(baseAgent.run(any())).thenAnswer(inv -> {
      for (DelegationRecorder.Contribution c : contributions) recorder.record(c);
      return AgentResult.of(jizza.getId(), "Jizza", text);
    });
  }

  @Test
  void answersInJizzasVoiceAndPersistsBothTurns() {
    answerWith("Your sleep is trending up.");

    AssistantReply reply = orchestrator.handle(userId, "How did I sleep?");

    assertThat(reply.text()).isEqualTo("Your sleep is trending up.");
    assertThat(messageRows).extracting(MessageEntity::getRole)
        .containsExactly(MessageRole.USER, MessageRole.ASSISTANT);
    assertThat(reply.conversationId()).isNotNull();
  }

  @Test
  void attributesTheSpecialistsThatContributedByName() {
    UUID vita = UUID.randomUUID();
    UUID kai = UUID.randomUUID();
    answerWith("Vita and Kai both looked into it.",
        new DelegationRecorder.Contribution(vita, "Vita", "sleep trend", "up 8%"),
        new DelegationRecorder.Contribution(kai, "Kai", "gym spend", "2,400 this month"));

    AssistantReply reply = orchestrator.handle(userId, "Is the gym worth it?");

    assertThat(reply.contributors()).extracting(AssistantReply.Contribution::agentName)
        .containsExactly("Vita", "Kai");
    assertThat(traceRows).hasSize(2);
    assertThat(traceRows).extracting(DelegationTraceEntity::getFromAgent)
        .containsOnly(jizza.getId());
  }

  @Test
  void theOnlyTextTheUserSeesIsJizzasOwn() {
    answerWith("Here is the short version.",
        new DelegationRecorder.Contribution(UUID.randomUUID(), "Vita",
            "sleep trend", "RAW SPECIALIST OUTPUT that should never surface"));

    AssistantReply reply = orchestrator.handle(userId, "How did I sleep?");

    assertThat(reply.text()).isEqualTo("Here is the short version.");
    assertThat(reply.contributors()).allSatisfy(c ->
        assertThat(c.summary()).doesNotContain("RAW SPECIALIST OUTPUT"));
    assertThat(messageRows.get(1).getContent()).doesNotContain("RAW SPECIALIST OUTPUT");
  }

  @Test
  void replaysPriorTurnsAsContextWithoutRepeatingTheCurrentOne() {
    answerWith("first answer");
    orchestrator.handle(userId, "first question");
    answerWith("second answer");

    orchestrator.handle(userId, "second question");

    var captor = org.mockito.ArgumentCaptor.forClass(AgentRequest.class);
    org.mockito.Mockito.verify(baseAgent, org.mockito.Mockito.atLeast(1)).run(captor.capture());
    AgentRequest last = captor.getAllValues().get(captor.getAllValues().size() - 1);
    assertThat(last.instruction()).isEqualTo("second question");
    assertThat(last.history()).extracting(Msg::text)
        .containsExactly("first question", "first answer");
  }

  @Test
  void continuesAnExistingConversationWhenOneIsNamed() {
    answerWith("ok");
    UUID conversationId = orchestrator.handle(userId, "hello").conversationId();

    answerWith("still ok");
    AssistantReply second = orchestrator.handle(userId, conversationId, "again");

    assertThat(second.conversationId()).isEqualTo(conversationId);
    assertThat(conversationRows).hasSize(1);
  }

  @Test
  void refusesAConversationBelongingToSomeoneElse() {
    answerWith("ok");
    UUID conversationId = orchestrator.handle(userId, "hello").conversationId();
    conversationRows.get(0).setUserId(UUID.randomUUID());

    assertThatThrownBy(() -> orchestrator.handle(userId, conversationId, "peek"))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void aUserWithoutAnOrchestratorAgentIsARecognisableError() {
    assertThatThrownBy(() -> orchestrator.handle(UUID.randomUUID(), "hello"))
        .isInstanceOf(NotFoundException.class)
        .hasMessageContaining("Orchestrator agent for user");
  }

  @Test
  void anEmptyModelReplyStillProducesSomethingHonest() {
    when(baseAgent.run(any())).thenReturn(AgentResult.of(jizza.getId(), "Jizza", "  "));

    AssistantReply reply = orchestrator.handle(userId, "hello?");

    assertThat(reply.text()).isEqualTo("I could not put together an answer for that.");
  }

  @Test
  void theTurnScopedRecorderDoesNotLeakBetweenTurns() {
    answerWith("one", new DelegationRecorder.Contribution(UUID.randomUUID(), "Vita", "t", "r"));
    orchestrator.handle(userId, "first");

    answerWith("two");
    AssistantReply second = orchestrator.handle(userId, "second");

    assertThat(second.contributors()).isEmpty();
  }
}
