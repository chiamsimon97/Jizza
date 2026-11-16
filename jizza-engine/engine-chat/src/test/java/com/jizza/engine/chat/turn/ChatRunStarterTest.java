package com.jizza.engine.chat.turn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.dto.RunDto;
import com.jizza.engine.api.enums.RunStatus;
import com.jizza.engine.api.error.ConflictException;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.workflow.library.WorkflowLibrary;
import com.jizza.engine.persistence.entity.ConversationEntity;
import com.jizza.engine.persistence.entity.MessageEntity;
import com.jizza.engine.persistence.repository.ConversationRepository;
import com.jizza.engine.persistence.repository.MessageRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Pageable;

class ChatRunStarterTest {

  private final UUID userId = UUID.randomUUID();
  private final UUID conversationId = UUID.randomUUID();
  private final UUID planId = UUID.randomUUID();
  private final UUID workflowId = UUID.randomUUID();
  private final UUID browserId = UUID.randomUUID();
  private MessageRepository messages;
  private WorkflowLibrary library;
  private TurnStore store;
  private ChatRunStarter starter;
  private MessageEntity reply;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    ConversationRepository conversations = mock(ConversationRepository.class);
    ConversationEntity conversation = new ConversationEntity();
    conversation.setId(conversationId);
    conversation.setUserId(userId);
    when(conversations.findById(conversationId)).thenReturn(Optional.of(conversation));
    messages = mock(MessageRepository.class);
    reply = new MessageEntity();
    reply.setMetadata(Json.read("""
        {"jobId":"j","actions":[{"type":"start_run","payload":{"planId":"%s","workflowId":"%s","name":"Train booking"}}]}"""
        .formatted(planId, workflowId)));
    when(messages.findByConversationIdOrderByCreatedAtDesc(eq(conversationId), any(Pageable.class)))
        .thenReturn(List.of(new MessageEntity(), reply));
    library = mock(WorkflowLibrary.class);
    when(library.reuse(eq(userId), eq(workflowId), any(), eq(WorkflowLibrary.Use.CHAT), anyString(), anyBoolean(), any()))
        .thenAnswer(inv -> new RunDto(UUID.randomUUID(), userId, workflowId, 1, inv.getArgument(4),
            RunStatus.RUNNING, null, Json.obj(), Instant.now(), Instant.now(), List.of()));
    ObjectProvider<WorkflowLibrary> p = mock(ObjectProvider.class);
    when(p.getIfAvailable()).thenReturn(library);
    store = mock(TurnStore.class);
    starter = new ChatRunStarter(conversations, messages, p, store);
  }

  @Test
  void startRunsItWithTheCardsValuesAndMarksTheCard() {
    RunDto run = starter.start(userId, conversationId, planId, workflowId,
        Json.read("{\"from\":\"MAS\",\"train\":\"\",\"rows\":[]}"), browserId);

    ArgumentCaptor<JsonNode> input = ArgumentCaptor.forClass(JsonNode.class);
    verify(library).reuse(eq(userId), eq(workflowId), input.capture(), eq(WorkflowLibrary.Use.CHAT),
        eq("chat:" + conversationId), eq(true), eq(browserId));
    assertThat(input.getValue().toString()).isEqualTo("{\"from\":\"MAS\"}");
    JsonNode card = reply.getMetadata().path("actions").get(0).path("payload");
    assertThat(card.path("runId").asText()).isEqualTo(run.id().toString());
    assertThat(card.path("browserId").asText()).isEqualTo(browserId.toString());
    verify(messages).save(reply);
    verify(store).note(eq(conversationId), eq("Started Train booking."), any());
  }

  @Test
  void aCardStartsOneRunOnly() {
    starter.start(userId, conversationId, planId, workflowId, Json.obj(), null);
    assertThatThrownBy(() -> starter.start(userId, conversationId, planId, workflowId, Json.obj(), null))
        .isInstanceOf(ConflictException.class);
  }

  @Test
  void onlyTheOwnersCardForThatWorkflow() {
    assertThatThrownBy(() -> starter.start(UUID.randomUUID(), conversationId, planId, workflowId, Json.obj(), null))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> starter.start(userId, conversationId, UUID.randomUUID(), workflowId, Json.obj(), null))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> starter.start(userId, conversationId, planId, UUID.randomUUID(), Json.obj(), null))
        .hasMessageContaining("different workflow");
    verify(library, never()).reuse(any(), any(), any(), any(), anyString(), anyBoolean(), any());
  }

  @Test
  void noThanksClosesTheCard() {
    starter.dismiss(userId, conversationId, planId);
    assertThat(reply.getMetadata().path("actions").get(0).path("payload").path("dismissed").asBoolean()).isTrue();
  }
}
