package com.jizza.knowledge.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jizza.common.enums.DataClassification;
import com.jizza.common.knowledge.KnowledgeEntry;
import com.jizza.common.knowledge.KnowledgeStore;
import com.jizza.common.tool.ToolCall;
import com.jizza.common.tool.ToolSpec;
import com.jizza.common.util.Json;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class KnowledgeToolProviderTest {

  private final UUID callingAgent = UUID.randomUUID();
  private final UUID otherAgent = UUID.randomUUID();

  private KnowledgeStore store;
  private KnowledgeToolProvider provider;

  @BeforeEach
  void setUp() {
    store = mock(KnowledgeStore.class);
    provider = new KnowledgeToolProvider(store);
  }

  @Test
  void offersTheThreeKnowledgeTools() {
    assertThat(provider.toolsFor(callingAgent)).extracting(ToolSpec::name)
        .containsExactlyInAnyOrder("knowledge_search", "knowledge_get", "knowledge_upsert");
  }

  @Test
  void noToolSchemaExposesAnAgentOrNamespaceParameter() {
    for (ToolSpec spec : provider.toolsFor(callingAgent)) {
      var properties = spec.inputSchema().path("properties");
      assertThat(properties.has("agentId")).as(spec.name()).isFalse();
      assertThat(properties.has("agent")).as(spec.name()).isFalse();
      assertThat(properties.has("namespace")).as(spec.name()).isFalse();
    }
  }

  @Test
  void searchesOnlyTheCallingAgentsNamespace() {
    when(store.search(eq(callingAgent), any(), anyInt())).thenReturn(List.of(
        new KnowledgeEntry(UUID.randomUUID(), null, callingAgent, "Steps", "9k yesterday",
            null, DataClassification.HEALTH, null, null, 0.8)));

    var result = provider.invoke(callingAgent,
        new ToolCall("knowledge_search", Json.obj().put("query", "steps")));

    assertThat(result.ok()).isTrue();
    assertThat(result.output().get(0).path("title").asText()).isEqualTo("Steps");
    verify(store).search(eq(callingAgent), eq("steps"), anyInt());
  }

  @Test
  void anAgentIdSmuggledIntoTheArgumentsIsIgnored() {
    when(store.search(any(), any(), anyInt())).thenReturn(List.of());

    provider.invoke(callingAgent, new ToolCall("knowledge_search",
        Json.obj().put("query", "x").put("agentId", otherAgent.toString())));

    // The namespace comes from the registry-supplied caller, never from the model's arguments.
    verify(store).search(eq(callingAgent), eq("x"), anyInt());
  }

  @Test
  void upsertPassesTheCallingAgentAsTheNamespace() {
    when(store.upsert(any(), any())).thenAnswer(inv -> {
      KnowledgeEntry e = inv.getArgument(1);
      return new KnowledgeEntry(UUID.randomUUID(), null, inv.getArgument(0), e.title(),
          e.content(), e.data(), e.classification(), null, null, null);
    });

    var result = provider.invoke(callingAgent, new ToolCall("knowledge_upsert",
        Json.obj().put("title", "Blood pressure").put("content", "118/74")
            .put("classification", "health")));

    assertThat(result.ok()).isTrue();
    ArgumentCaptor<KnowledgeEntry> captor = ArgumentCaptor.forClass(KnowledgeEntry.class);
    verify(store).upsert(eq(callingAgent), captor.capture());
    assertThat(captor.getValue().agentId()).isEqualTo(callingAgent);
    assertThat(captor.getValue().classification()).isEqualTo(DataClassification.HEALTH);
  }

  @Test
  void getReportsAMissingEntryRatherThanThrowing() {
    UUID id = UUID.randomUUID();
    when(store.get(callingAgent, id)).thenReturn(Optional.empty());
    var result = provider.invoke(callingAgent,
        new ToolCall("knowledge_get", Json.obj().put("id", id.toString())));
    assertThat(result.ok()).isFalse();
    assertThat(result.error()).contains("No entry with id");
  }

  @Test
  void aMalformedIdIsAClearToolErrorNotAnException() {
    var result = provider.invoke(callingAgent,
        new ToolCall("knowledge_get", Json.obj().put("id", "not-a-uuid")));
    assertThat(result.ok()).isFalse();
    assertThat(result.error()).contains("must be a knowledge entry id");
  }
}
