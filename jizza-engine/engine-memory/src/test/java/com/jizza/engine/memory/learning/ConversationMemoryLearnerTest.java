package com.jizza.engine.memory.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.enums.MemoryCategory;
import com.jizza.engine.api.enums.MemorySource;
import com.jizza.engine.api.enums.MemoryWriteOutcome;
import com.jizza.engine.api.enums.ModelTier;
import com.jizza.engine.api.enums.SubjectKind;
import com.jizza.engine.api.llm.CostAccountant;
import com.jizza.engine.api.llm.LlmClient;
import com.jizza.engine.api.llm.LlmResponse;
import com.jizza.engine.api.memory.Memory;
import com.jizza.engine.api.memory.MemoryDraft;
import com.jizza.engine.api.memory.MemoryLearner;
import com.jizza.engine.api.memory.MemorySettings;
import com.jizza.engine.api.memory.MemoryStore;
import com.jizza.engine.api.memory.MemorySubject;
import com.jizza.engine.api.memory.MemoryWrite;
import com.jizza.engine.api.memory.SubjectDraft;
import com.jizza.engine.memory.config.MemoryProperties;
import com.jizza.engine.memory.config.MemoryScopes;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ConversationMemoryLearnerTest {

  private final UUID userId = UUID.randomUUID();
  private MemoryStore store;
  private LlmClient llm;
  private CostAccountant costs;
  private MemoryProperties props;
  private ConversationMemoryLearner learner;
  private final List<MemorySubject> subjects = new ArrayList<>();

  @BeforeEach
  void setUp() {
    store = mock(MemoryStore.class);
    llm = mock(LlmClient.class);
    costs = mock(CostAccountant.class);
    props = new MemoryProperties();
    props.getExtraction().setAsync(false);
    when(costs.withinBudget(userId)).thenReturn(true);
    when(llm.modelFor(ModelTier.ROUTING)).thenReturn("cheap-model");
    when(store.settings(userId)).thenReturn(MemorySettings.defaults());
    when(store.subjects(eq(userId), any())).thenAnswer(inv -> List.copyOf(subjects));
    when(store.upsertSubject(eq(userId), any(), any())).thenAnswer(inv -> {
      SubjectDraft d = inv.getArgument(1);
      MemorySubject s = new MemorySubject(UUID.randomUUID(), userId, d.kind(), d.name(), d.relation(),
          List.of(), null, null, d.domains(), List.of(), null, null);
      subjects.add(s);
      return s;
    });
    when(store.remember(eq(userId), any(), any())).thenAnswer(inv -> new MemoryWrite(
        memory(inv.getArgument(1)), MemoryWriteOutcome.CREATED, null));
    learner = new ConversationMemoryLearner(store, llm, costs, new MemoryScopes(props), props,
        mock(AuditLog.class));
  }

  @Test
  void promotesConfidentDurableFactsAndDropsTheRest() {
    reply("""
        Here you go:
        ```json
        {"facts":[
          {"content":"Priya, the user's wife, has a moderate risk tolerance","category":"finance",
           "key":"risk_tolerance","about":[{"name":"Priya","kind":"person","relation":"wife"}],
           "classification":"financial","confidence":0.92,"importance":0.6},
          {"content":"The user might buy a car","category":"general","confidence":0.4}
        ],
         "links":[]}
        ```""");

    int learned = learner.learn(turn("My wife Priya is fine with moderate risk, maybe I'll buy a car"));

    assertThat(learned).isEqualTo(1);
    ArgumentCaptor<MemoryDraft> draft = ArgumentCaptor.forClass(MemoryDraft.class);
    verify(store).remember(eq(userId), draft.capture(), any());
    assertThat(draft.getValue().category()).isEqualTo(MemoryCategory.FINANCE);
    assertThat(draft.getValue().factKey()).isEqualTo("risk_tolerance");
    assertThat(draft.getValue().source()).isEqualTo(MemorySource.CONVERSATION);
    assertThat(draft.getValue().confirmed()).isFalse();
    assertThat(draft.getValue().sourceRef()).startsWith("message:");
    assertThat(draft.getValue().subjectIds()).hasSize(1);
    assertThat(subjects).singleElement().satisfies(s -> {
      assertThat(s.name()).isEqualTo("Priya");
      assertThat(s.relation()).isEqualTo("wife");
      assertThat(s.kind()).isEqualTo(SubjectKind.PERSON);
    });
    verify(costs).record(eq(userId), any(), any(Integer.class), any(Integer.class), eq("memory:extract"));
  }

  @Test
  void reusesAKnownSubjectReferredToByRelation() {
    subjects.add(new MemorySubject(UUID.randomUUID(), userId, SubjectKind.PERSON, "Priya", "wife",
        List.of(), null, null, List.of(), List.of(), null, null));
    reply("""
        {"facts":[{"content":"Priya prefers index funds","category":"preference",
          "about":[{"name":"wife","kind":"person"}],"confidence":0.9}],"links":[]}""");

    learner.learn(turn("My wife prefers index funds over picking stocks"));

    verify(store, never()).upsertSubject(any(), any(), any());
  }

  @Test
  void doesNothingWhenTheUserTurnedAutoLearnOff() {
    when(store.settings(userId)).thenReturn(new MemorySettings(false, null));
    learner.afterTurn(turn("My wife Priya is fine with moderate risk"));
    verifyNoInteractions(llm);
  }

  @Test
  void skipsShortMessagesAndAnExhaustedBudget() {
    learner.afterTurn(turn("thanks"));
    when(costs.withinBudget(userId)).thenReturn(false);
    learner.afterTurn(turn("My wife Priya is fine with moderate risk"));
    verifyNoInteractions(llm);
  }

  @Test
  void aModelReplyThatIsNotJsonStoresNothing() {
    reply("I could not find anything worth remembering.");
    assertThat(learner.learn(turn("My wife Priya is fine with moderate risk"))).isZero();
    verify(store, never()).remember(any(), any(), any());
  }

  @Test
  void aFailureNeverEscapesTheTurn() {
    when(llm.complete(any(String.class), any(), any())).thenThrow(new IllegalStateException("model down"));
    learner.afterTurn(turn("My wife Priya is fine with moderate risk"));
  }

  private void reply(String text) {
    when(llm.complete(any(String.class), any(), any()))
        .thenReturn(new LlmResponse(text, List.of(), "end_turn", 100, 50, "cheap-model"));
  }

  private MemoryLearner.Turn turn(String userText) {
    return new MemoryLearner.Turn(userId, UUID.randomUUID(), UUID.randomUUID(), userText, "Noted.");
  }

  private Memory memory(MemoryDraft d) {
    return new Memory(UUID.randomUUID(), userId, d.category(), List.of(), null, d.content(),
        d.factKey(), null, 0.9, 0.5, d.source(), d.sourceRef(), false, null, null, d.subjectIds(),
        null, null, null, null, null);
  }
}
