package com.jizza.platform.usecase.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jizza.engine.api.dto.ConversationDto;
import com.jizza.engine.api.orchestrator.ConversationStore;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ConversationUseCaseTest {

  private ConversationStore store;
  private ConversationUseCase useCase;

  private final UUID userId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    store = mock(ConversationStore.class);
    useCase = new ConversationUseCase(store, mock(com.jizza.engine.api.chat.ChatService.class));
  }

  @Test
  void listDelegatesToStoreWithRequestedLimit() {
    var conversation = new ConversationDto(UUID.randomUUID(), Instant.now(), List.of());
    when(store.list(userId, 10)).thenReturn(List.of(conversation));

    var result = useCase.list(userId, 10);

    assertThat(result).containsExactly(conversation);
  }

  @Test
  void listCapsLimitAtFifty() {
    when(store.list(userId, 50)).thenReturn(List.of());

    useCase.list(userId, 999);

    org.mockito.Mockito.verify(store).list(userId, 50);
  }

  @Test
  void getDelegatesToStore() {
    var id = UUID.randomUUID();
    var conversation = new ConversationDto(id, Instant.now(), List.of());
    when(store.get(userId, id)).thenReturn(conversation);

    assertThat(useCase.get(userId, id)).isEqualTo(conversation);
  }

  @Test
  void messagesDelegatesToStore() {
    var id = UUID.randomUUID();
    var msg = new ConversationDto.MessageDto(UUID.randomUUID(), "user", "hello", Instant.now());
    when(store.messages(userId, id)).thenReturn(List.of(msg));

    assertThat(useCase.messages(userId, id)).containsExactly(msg);
  }
}
