package com.jizza.engine.chat.thread;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.chat.ChatScope;
import com.jizza.engine.api.orchestrator.UiAction;
import com.jizza.engine.api.tool.ToolCall;
import com.jizza.engine.api.tool.ToolResult;
import com.jizza.engine.api.tool.ToolSpec;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.chat.config.ChatProperties;
import com.jizza.engine.persistence.entity.AgentEntity;
import com.jizza.engine.persistence.entity.ConversationEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ThreadToolProviderTest {

  private final UUID userId = UUID.randomUUID();
  private final UUID jizzaId = UUID.randomUUID();
  private final UUID mainId = UUID.randomUUID();
  private final ThreadService threads = mock(ThreadService.class);
  private final ChatProperties props = new ChatProperties();
  private final ThreadToolProvider provider = new ThreadToolProvider(threads, props, mock(AuditLog.class));
  private AgentEntity kai;

  @BeforeEach
  void team() {
    kai = new AgentEntity();
    kai.setId(UUID.randomUUID());
    kai.setUserId(userId);
    kai.setKey("finance");
    kai.setName("Kai");
    kai.setDescription("Money: accounts, spending, budgets, bills, investments, tax.");
    kai.setEnabled(true);
    kai.setStatus("active");
    when(threads.leads(userId)).thenReturn(List.of(kai));
    when(threads.openThreads(eq(userId), anyInt())).thenReturn(List.of());
    when(threads.agent(kai.getId())).thenReturn(Optional.of(kai));
  }

  private List<String> names(UUID agentId) {
    return provider.toolsFor(agentId).stream().map(ToolSpec::name).toList();
  }

  @Test
  void outsideAConversationThereAreNoThreadTools() {
    assertThat(names(jizzaId)).isEmpty();
  }

  @Test
  void inTheMainChatOnlyJizzaCanOpenAThread() {
    ChatScope.Scope main = ChatScope.Scope.main(userId, mainId, jizzaId, true);

    assertThat(ChatScope.run(main, () -> names(jizzaId))).containsExactly(ThreadToolProvider.START);
    // A specialist Jizza delegates to on the same thread is not the one talking to the user.
    assertThat(ChatScope.run(main, () -> names(kai.getId()))).isEmpty();
    String description = ChatScope.run(main, () -> provider.toolsFor(jizzaId)).get(0).description();
    assertThat(description).contains("- finance: Kai -- Money:").contains("investments");
  }

  @Test
  void openThreadsCanBeContinued() {
    ConversationEntity open = thread(kai.getId());
    when(threads.openThreads(eq(userId), anyInt())).thenReturn(List.of(open));

    List<ToolSpec> tools = ChatScope.run(ChatScope.Scope.main(userId, mainId, jizzaId, true),
        () -> provider.toolsFor(jizzaId));

    assertThat(tools).extracting(ToolSpec::name)
        .containsExactly(ThreadToolProvider.START, ThreadToolProvider.CONTINUE);
    assertThat(tools.get(1).description()).contains(open.getId().toString()).contains("led by Kai");
  }

  @Test
  void aChatWithoutThreadsOffersNone() {
    assertThat(ChatScope.run(ChatScope.Scope.main(userId, mainId, jizzaId, false), () -> names(jizzaId)))
        .isEmpty();
  }

  @Test
  void aNewTopicForAnotherSpecialistGetsItsOwnThreadEvenAQuickOne() {
    String description = ChatScope.run(ChatScope.Scope.main(userId, mainId, jizzaId, true),
        () -> provider.toolsFor(jizzaId)).get(0).description();
    assertThat(description).contains("even a quick question").contains("blood tests")
        .doesNotContain("use delegate_to_specialist");
  }

  @Test
  void turnedOffThereAreNone() {
    props.getThreads().setEnabled(false);

    assertThat(ChatScope.run(ChatScope.Scope.main(userId, mainId, jizzaId, true), () -> names(jizzaId))).isEmpty();
  }

  @Test
  void startingAThreadOpensItHandsTheMessageOverAndShowsACard() {
    ConversationEntity created = thread(kai.getId());
    when(threads.open(eq(userId), eq(mainId), eq(kai), anyString(), anyString())).thenReturn(created);
    ChatScope.Scope main = ChatScope.Scope.main(userId, mainId, jizzaId, true);

    ToolResult result = ChatScope.run(main, () -> provider.invoke(jizzaId, new ToolCall(
        ThreadToolProvider.START, Json.obj().put("lead", "finance").put("title", "Index fund plan")
            .put("topic", "Monthly index fund investing").put("message", "Help me start investing 500 a month"))));

    assertThat(result.ok()).isTrue();
    verify(threads).deliver(eq(userId), eq(created.getId()), eq("Help me start investing 500 a month"),
        contains("Help me start investing 500 a month"), eq("handoff"));
    List<UiAction> actions = ChatScope.actions(main);
    assertThat(actions).hasSize(1);
    assertThat(actions.get(0).type()).isEqualTo(UiAction.THREAD_OPENED);
    assertThat(actions.get(0).payload().path("leadName").asText()).isEqualTo("Kai");
    assertThat(actions.get(0).payload().path("conversationId").asText()).isEqualTo(created.getId().toString());
  }

  @Test
  void anUnknownLeadIsRefusedWithoutOpeningAnything() {
    ToolResult result = ChatScope.run(ChatScope.Scope.main(userId, mainId, jizzaId, true), () -> provider.invoke(jizzaId,
        new ToolCall(ThreadToolProvider.START, Json.obj().put("lead", "astrology").put("title", "x")
            .put("topic", "x").put("message", "x"))));

    assertThat(result.ok()).isFalse();
    verify(threads, never()).open(any(), any(), any(), any(), any());
  }

  @Test
  void inAThreadItsLeadCanHandBackAndKeepNotes() {
    ConversationEntity thread = thread(kai.getId());
    ChatScope.Scope scope = ChatScope.Scope.thread(userId, thread.getId(), mainId, kai.getId(),
        thread.getTitle(), thread.getTopic());

    assertThat(ChatScope.run(scope, () -> names(kai.getId())))
        .containsExactly(ThreadToolProvider.HAND_BACK, ThreadToolProvider.NOTE);
    assertThat(ChatScope.run(scope, () -> names(jizzaId))).isEmpty();

    ToolResult result = ChatScope.run(scope, () -> provider.invoke(kai.getId(), new ToolCall(
        ThreadToolProvider.HAND_BACK, Json.obj().put("message", "Book me a dentist").put("reason", "health"))));

    assertThat(result.ok()).isTrue();
    verify(threads).deliver(eq(userId), eq(mainId), eq("Book me a dentist"), contains("Index fund plan"),
        eq("handback"));
    verify(threads).noteIn(eq(mainId), contains("Kai passed this back"), any());
    assertThat(ChatScope.actions(scope)).extracting(UiAction::type).containsExactly(UiAction.THREAD_HANDBACK);
  }

  @Test
  void aNoteGoesToTheSectionAsked() {
    ConversationEntity thread = thread(kai.getId());
    ChatScope.Scope scope = ChatScope.Scope.thread(userId, thread.getId(), mainId, kai.getId(),
        thread.getTitle(), thread.getTopic());

    ChatScope.run(scope, () -> provider.invoke(kai.getId(), new ToolCall(ThreadToolProvider.NOTE,
        Json.obj().put("section", "decisions").put("note", "Prefers low-cost ETFs"))));

    verify(threads).note(userId, thread.getId(), ThreadGuides.DECISIONS, "Prefers low-cost ETFs");
  }

  private ConversationEntity thread(UUID ownerId) {
    ConversationEntity t = new ConversationEntity();
    t.setId(UUID.randomUUID());
    t.setUserId(userId);
    t.setKind(ConversationEntity.KIND_THREAD);
    t.setParentId(mainId);
    t.setOwnerAgentId(ownerId);
    t.setTitle("Index fund plan");
    t.setTopic("Monthly index fund investing");
    return t;
  }
}
