package com.jizza.engine.chat.thread;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.chat.ChatScope;
import com.jizza.engine.api.enums.ModelTier;
import com.jizza.engine.api.llm.CostAccountant;
import com.jizza.engine.api.llm.LlmClient;
import com.jizza.engine.api.llm.LlmResponse;
import com.jizza.engine.api.llm.Msg;
import com.jizza.engine.api.orchestrator.Orchestrator;
import com.jizza.engine.api.orchestrator.UiAction;
import com.jizza.engine.chat.config.ChatProperties;
import com.jizza.engine.persistence.entity.AgentEntity;
import com.jizza.engine.persistence.entity.ConversationEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ThreadCoordinatorTest {

  private final UUID userId = UUID.randomUUID();
  private final UUID jizzaId = UUID.randomUUID();
  private final UUID mainId = UUID.randomUUID();
  private final ThreadService threads = mock(ThreadService.class);
  private final LlmClient llm = mock(LlmClient.class);
  private final CostAccountant costs = mock(CostAccountant.class);
  private final ChatProperties props = new ChatProperties();
  private final ThreadCoordinator coordinator =
      new ThreadCoordinator(threads, llm, costs, props, mock(AuditLog.class));
  private AgentEntity kai;
  private ConversationEntity mine;

  @BeforeEach
  void setUp() {
    kai = new AgentEntity();
    kai.setId(UUID.randomUUID());
    kai.setUserId(userId);
    kai.setKey("finance");
    kai.setName("Kai");
    mine = new ConversationEntity();
    mine.setId(UUID.randomUUID());
    mine.setUserId(userId);
    mine.setKind(ConversationEntity.KIND_THREAD);
    mine.setParentId(mainId);
    mine.setOwnerAgentId(kai.getId());
    mine.setTitle("My monthly investing plan");
    mine.setTopic("The user's own plan to invest 500 a month");
    when(costs.withinBudget(userId)).thenReturn(true);
    when(llm.modelFor(any())).thenReturn("routing");
    when(threads.leads(userId)).thenReturn(List.of(kai));
    when(threads.threadsOf(userId, mainId)).thenReturn(List.of(mine));
    when(threads.agent(kai.getId())).thenReturn(Optional.of(kai));
  }

  private void modelSays(String json) {
    when(llm.complete(anyString(), anyList(), anyList())).thenReturn(LlmResponse.text(json));
  }

  private ChatScope.Scope main() {
    return ChatScope.Scope.main(userId, mainId, jizzaId, true);
  }

  @Test
  void anotherPersonsPlanGetsItsOwnThreadWithTheSameLead() {
    modelSays("""
        {"route":"new","lead":"finance","title":"Investing plan for Dad","topic":"An investment plan for the user's father"}""");
    ConversationEntity dads = new ConversationEntity();
    dads.setId(UUID.randomUUID());
    dads.setTitle("Investing plan for Dad");
    when(threads.startThread(userId, mainId, kai, "Investing plan for Dad",
        "An investment plan for the user's father", "Plan investments for my dad too"))
        .thenReturn(new ThreadService.Moved(dads, kai, false));

    Optional<Orchestrator.Turn> turn = coordinator.coordinate(main(), "Plan investments for my dad too", List.of());

    assertThat(turn).isPresent();
    assertThat(turn.get().text()).contains("Kai").contains("Investing plan for Dad");
    assertThat(turn.get().actions()).extracting(UiAction::type).containsExactly(UiAction.THREAD_OPENED);
  }

  @Test
  void aFollowUpGoesBackToItsThreadEvenWhenResolved() {
    mine.setArchivedAt(java.time.Instant.now());
    modelSays("{\"route\":\"thread\",\"thread_id\":\"" + mine.getId() + "\"}");
    when(threads.continueThread(userId, mine, "Did we pick a fund?"))
        .thenReturn(new ThreadService.Moved(mine, kai, true));

    Optional<Orchestrator.Turn> turn = coordinator.coordinate(main(), "Did we pick a fund?", List.of(Msg.user("hi")));

    assertThat(turn).isPresent();
    assertThat(turn.get().text()).contains("Kai is picking this up");
    verify(threads).continueThread(userId, mine, "Did we pick a fund?");
  }

  @Test
  void aThreadFromAnotherChatOrAnUnknownLeadMovesNothing() {
    modelSays("{\"route\":\"thread\",\"thread_id\":\"" + UUID.randomUUID() + "\"}");
    assertThat(coordinator.coordinate(main(), "Did we pick a fund?", List.of())).isEmpty();

    modelSays("{\"route\":\"new\",\"lead\":\"astrology\",\"title\":\"Stars\"}");
    assertThat(coordinator.coordinate(main(), "What do the stars say?", List.of())).isEmpty();
    verify(threads, never()).startThread(any(), any(), any(), any(), any(), any());
  }

  @Test
  void inAThreadAnotherPurposeGoesBackToTheMainChat() {
    ChatScope.Scope scope = ChatScope.Scope.thread(userId, mine.getId(), mainId, kai.getId(),
        mine.getTitle(), mine.getTopic());
    modelSays("{\"route\":\"main\",\"reason\":\"small talk\"}");

    Optional<Orchestrator.Turn> turn = coordinator.coordinate(scope, "How are you?", List.of());

    assertThat(turn).isPresent();
    assertThat(turn.get().actions()).extracting(UiAction::type).containsExactly(UiAction.THREAD_HANDBACK);
    verify(threads).handBack(scope, "Kai", "How are you?", "small talk");
  }

  @Test
  void stayingPutOrAPlainChatOrABrokenAnswerMovesNothing() {
    ChatScope.Scope scope = ChatScope.Scope.thread(userId, mine.getId(), mainId, kai.getId(),
        mine.getTitle(), mine.getTopic());
    modelSays("{\"route\":\"thread\",\"thread_id\":\"" + mine.getId() + "\"}");
    assertThat(coordinator.coordinate(scope, "Which fund?", List.of())).isEmpty();

    modelSays("not json");
    assertThat(coordinator.coordinate(main(), "Which fund?", List.of())).isEmpty();

    assertThat(coordinator.coordinate(ChatScope.Scope.main(userId, mainId, jizzaId, false), "x", List.of())).isEmpty();
    props.getThreads().getRouter().setEnabled(false);
    assertThat(coordinator.coordinate(main(), "x", List.of())).isEmpty();
    assertThat(ModelTier.ROUTING).isEqualTo(props.getThreads().getRouter().getTier());
  }
}
