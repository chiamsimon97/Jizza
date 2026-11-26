package com.jizza.engine.chat.thread;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jizza.engine.api.chat.ChatScope;
import com.jizza.engine.api.llm.CostAccountant;
import com.jizza.engine.api.llm.LlmClient;
import com.jizza.engine.api.llm.LlmResponse;
import com.jizza.engine.api.llm.Msg;
import com.jizza.engine.chat.config.ChatProperties;
import com.jizza.engine.persistence.entity.AgentEntity;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SpecialistRouterTest {

  private final UUID userId = UUID.randomUUID();
  private final UUID chatId = UUID.randomUUID();
  private final ThreadService threads = mock(ThreadService.class);
  private final LlmClient llm = mock(LlmClient.class);
  private final CostAccountant costs = mock(CostAccountant.class);
  private final ChatProperties props = new ChatProperties();
  private final SpecialistRouter router = new SpecialistRouter(threads, llm, costs, props);
  private AgentEntity kai;
  private AgentEntity vita;

  @BeforeEach
  void setUp() {
    kai = agent("finance", "Kai");
    vita = agent("health", "Vita");
    when(costs.withinBudget(userId)).thenReturn(true);
    when(llm.modelFor(any())).thenReturn("routing");
    when(threads.leads(userId)).thenReturn(List.of(kai, vita));
  }

  private AgentEntity agent(String key, String name) {
    AgentEntity a = new AgentEntity();
    a.setId(UUID.randomUUID());
    a.setUserId(userId);
    a.setKey(key);
    a.setName(name);
    return a;
  }

  private void modelSays(String json) {
    when(llm.complete(anyString(), anyList(), anyList())).thenReturn(LlmResponse.text(json));
  }

  private ChatScope.Scope plain() {
    return ChatScope.Scope.main(userId, chatId, UUID.randomUUID(), false);
  }

  @Test
  void aMoneyGoalGoesToKai() {
    modelSays("{\"answer\":\"finance\",\"reason\":\"savings goal\"}");
    assertThat(router.route(plain(), "chat", "Save 1L for silver by next year", List.of()))
        .contains(kai);
  }

  @Test
  void aFollowUpIsDecidedWithTheExchangeInView() {
    modelSays("{\"answer\":\"Kai\"}");
    List<Msg> history = List.of(Msg.user("Save 1L for silver by next year"),
        Msg.assistant("I've added the goal. Want a monthly reminder?"));
    assertThat(router.route(plain(), null, "Yes set a reminder", history)).contains(kai);
  }

  @Test
  void jizzaKeepsEverythingElse() {
    modelSays("{\"answer\":\"jizza\"}");
    assertThat(router.route(plain(), "chat", "Thanks!", List.of())).isEmpty();
  }

  @Test
  void onlyTheConfiguredSpecialistsTakeMessages() {
    // Vita is not in jizza.chat.specialists.keys, so her name leaves the message with Jizza.
    modelSays("{\"answer\":\"health\"}");
    assertThat(router.route(plain(), "chat", "I slept badly", List.of())).isEmpty();
  }

  @Test
  void aBadAnswerLeavesItWithJizza() {
    modelSays("not json");
    assertThat(router.route(plain(), "chat", "Save for a car", List.of())).isEmpty();
    when(llm.complete(anyString(), anyList(), anyList())).thenThrow(new RuntimeException("down"));
    assertThat(router.route(plain(), "chat", "Save for a car", List.of())).isEmpty();
  }

  @Test
  void threadsWorkflowScreensAndSpentBudgetsAreLeftAlone() {
    modelSays("{\"answer\":\"finance\"}");
    assertThat(router.route(ChatScope.Scope.main(userId, chatId, UUID.randomUUID(), true),
        "chat", "Save for a car", List.of())).isEmpty();
    assertThat(router.route(plain(), "workflow_planner", "Save for a car", List.of())).isEmpty();
    when(costs.withinBudget(userId)).thenReturn(false);
    assertThat(router.route(plain(), "chat", "Save for a car", List.of())).isEmpty();
    verify(llm, never()).complete(anyString(), anyList(), anyList());
  }
}
