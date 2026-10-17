package com.jizza.platform.usecase.usage;

import com.jizza.engine.api.dto.UsageEntryDto;
import com.jizza.engine.api.llm.UsageStore;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Exposes LLM cost and token usage to the user (R49). */
@Service
public class UsageUseCase {

  private static final int MAX_ENTRIES = 200;

  private final UsageStore store;

  public UsageUseCase(UsageStore store) {
    this.store = store;
  }

  public UsageStore.Summary summary(UUID userId) {
    return store.summary(userId);
  }

  public List<UsageEntryDto> recent(UUID userId, int limit) {
    return store.recent(userId, Math.min(limit, MAX_ENTRIES));
  }
}
