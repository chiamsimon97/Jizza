package com.jizza.platform.usecase.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jizza.engine.api.dto.UsageEntryDto;
import com.jizza.engine.api.llm.UsageStore;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class UsageUseCaseTest {

  private UsageStore store;
  private UsageUseCase useCase;
  private final UUID userId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    store = mock(UsageStore.class);
    useCase = new UsageUseCase(store);
  }

  @Test
  void summaryDelegatesToStore() {
    var summary = new UsageStore.Summary(Instant.now(), Instant.now(), 1000L, 200L,
        BigDecimal.valueOf(0.01), true, BigDecimal.valueOf(5.0), List.of());
    when(store.summary(userId)).thenReturn(summary);

    assertThat(useCase.summary(userId)).isEqualTo(summary);
  }

  @Test
  void recentDelegatesToStoreWithRequestedLimit() {
    var entry = new UsageEntryDto(UUID.randomUUID(), "claude-model", "agent:jizza",
        500, 100, BigDecimal.valueOf(0.005), Instant.now());
    when(store.recent(userId, 10)).thenReturn(List.of(entry));

    assertThat(useCase.recent(userId, 10)).containsExactly(entry);
  }

  @Test
  void recentCapsLimitAtTwoHundred() {
    when(store.recent(userId, 200)).thenReturn(List.of());

    useCase.recent(userId, 9999);

    verify(store).recent(userId, 200);
  }
}
