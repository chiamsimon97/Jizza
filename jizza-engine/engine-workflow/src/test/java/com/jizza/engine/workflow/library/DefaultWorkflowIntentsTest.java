package com.jizza.engine.workflow.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jizza.engine.api.enums.ModelTier;
import com.jizza.engine.api.llm.LlmClient;
import com.jizza.engine.api.llm.LlmResponse;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.workflow.library.ReuseProfile;
import com.jizza.engine.api.workflow.library.WorkflowLibrary;
import com.jizza.engine.persistence.entity.WorkflowEntity;
import com.jizza.engine.persistence.repository.WorkflowRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

class DefaultWorkflowIntentsTest {

  @Test
  void readsASummaryAndSomeExamples() {
    var s = DefaultWorkflowIntents.parse("""
        {"summary": "Books a train ticket on the railway site",
         "examples": ["book a train ticket", "", "railway booking Chennai to Delhi", "a", "b", "c", "d"]}""");
    assertThat(s.summary()).startsWith("Books a train");
    assertThat(s.examples()).hasSize(5).first().isEqualTo("book a train ticket");
    assertThat(DefaultWorkflowIntents.parse("{\"summary\": \"x\", \"examples\": []}")).isNull();
    assertThat(DefaultWorkflowIntents.parse("no")).isNull();
  }

  @Test
  @SuppressWarnings("unchecked")
  void keepsTheGuardrailsThePersonSet() {
    UUID userId = UUID.randomUUID();
    WorkflowEntity w = new WorkflowEntity();
    w.setId(UUID.randomUUID());
    w.setUserId(userId);
    w.setName("Simon");
    w.setGraph(Json.read("{\"nodes\":[{\"id\":\"n1\",\"label\":\"Open\",\"config\":{\"url\":\"https://rail.example/\"}}]}"));
    ReuseProfile.Guardrails mine = new ReuseProfile.Guardrails(false, false, 3, List.of("only weekdays"), null);
    w.setReuse(new ReuseProfile("Simon", List.of(), List.of(), mine, null).toJson());
    WorkflowRepository repo = mock(WorkflowRepository.class);
    when(repo.findById(w.getId())).thenReturn(Optional.of(w));
    WorkflowLibrary lib = mock(WorkflowLibrary.class);
    LlmClient llm = mock(LlmClient.class);
    when(llm.modelFor(ModelTier.ROUTING)).thenReturn("cheap");
    when(llm.complete(eq("cheap"), anyList(), anyList())).thenReturn(new LlmResponse(
        "{\"summary\":\"Books a train ticket\",\"examples\":[\"book a train\"]}", List.of(), "end_turn", 300, 40, "cheap"));
    var intents = new DefaultWorkflowIntents(repo, provider(lib), provider(llm), provider(null));

    intents.suggest(userId, w.getId());

    ArgumentCaptor<ReuseProfile> saved = ArgumentCaptor.forClass(ReuseProfile.class);
    org.mockito.Mockito.verify(lib).describe(eq(userId), eq(w.getId()), saved.capture());
    assertThat(saved.getValue().examples()).containsExactly("book a train");
    assertThat(saved.getValue().guardrails().maxRunsPerDay()).isEqualTo(3);
    assertThat(saved.getValue().guardrails().confirmBeforeRun()).isFalse();
    assertThat(DefaultWorkflowIntents.describe(w)).contains("rail.example");
  }

  @SuppressWarnings("unchecked")
  private static <T> ObjectProvider<T> provider(T value) {
    ObjectProvider<T> p = mock(ObjectProvider.class);
    when(p.getIfAvailable()).thenReturn(value);
    return p;
  }
}
