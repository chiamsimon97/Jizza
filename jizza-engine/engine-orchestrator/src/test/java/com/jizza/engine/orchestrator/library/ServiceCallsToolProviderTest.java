package com.jizza.engine.orchestrator.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jizza.engine.agents.registry.ServiceCallJournal;
import com.jizza.engine.api.activity.Activity;
import com.jizza.engine.api.activity.ActivityEvent;
import com.jizza.engine.api.activity.ActivitySink;
import com.jizza.engine.api.tool.ToolCall;
import com.jizza.engine.api.tool.ToolResult;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.persistence.entity.AgentEntity;
import com.jizza.engine.persistence.entity.GeneratedToolEntity;
import com.jizza.engine.persistence.repository.AgentRepository;
import com.jizza.engine.persistence.repository.GeneratedToolRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class ServiceCallsToolProviderTest {

  @Test
  @SuppressWarnings("unchecked")
  void listsTheConversationsCallsAndWhichCanRunAsSteps() {
    UUID userId = UUID.randomUUID();
    UUID conversationId = UUID.randomUUID();
    AgentEntity jizza = new AgentEntity();
    jizza.setId(UUID.randomUUID());
    jizza.setUserId(userId);
    jizza.setOrchestrator(true);
    AgentRepository agents = mock(AgentRepository.class);
    when(agents.findById(jizza.getId())).thenReturn(Optional.of(jizza));

    ServiceCallJournal journal = new ServiceCallJournal();
    journal.record(conversationId, UUID.randomUUID(), "Kai", "metals_get_rate",
        Json.obj().put("metal", "gold"));
    journal.record(conversationId, UUID.randomUUID(), "Kai", "vault_read", null);
    GeneratedToolEntity action = new GeneratedToolEntity();
    action.setId(UUID.randomUUID());
    action.setName("metals_get_rate");
    action.setSideEffecting(false);
    GeneratedToolRepository tools = mock(GeneratedToolRepository.class);
    when(tools.findEnabledForUser(userId)).thenReturn(List.of(action));
    ObjectProvider<ServiceCallJournal> journals = mock(ObjectProvider.class);
    when(journals.getIfAvailable()).thenReturn(journal);
    var provider = new ServiceCallsToolProvider(agents, new CallSteps(journals, tools));
    ActivitySink sink = new ActivitySink() {
      @Override public void emit(ActivityEvent event) {}
      @Override public boolean cancelled() { return false; }
      @Override public UUID conversationId() { return conversationId; }
    };

    ToolResult result = Activity.run(sink, () -> provider.invoke(jizza.getId(),
        new ToolCall("c1", ServiceCallsToolProvider.RECENT, Json.obj())));

    var calls = result.output().path("calls");
    assertThat(calls).hasSize(2);
    assertThat(calls.get(0).path("call").asInt()).isEqualTo(1);
    assertThat(calls.get(0).path("as_step").asBoolean()).isTrue();
    assertThat(calls.get(0).path("arguments").path("metal").asText()).isEqualTo("gold");
    assertThat(calls.get(1).path("as_step").asBoolean()).isFalse();
    assertThat(calls.get(1).path("arguments").asText()).isEqualTo("(hidden)");
  }
}
