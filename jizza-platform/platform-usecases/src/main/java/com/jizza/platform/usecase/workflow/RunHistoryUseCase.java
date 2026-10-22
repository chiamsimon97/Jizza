package com.jizza.platform.usecase.workflow;

import com.jizza.engine.api.dto.RunHistoryDto;
import com.jizza.engine.api.workflow.RunHistory;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Every run of a person's workflows, and each one step by step. */
@Service
public class RunHistoryUseCase {

  private final RunHistory history;

  public RunHistoryUseCase(RunHistory history) {
    this.history = history;
  }

  public RunHistoryDto.Page search(UUID userId, RunHistoryDto.Query query) {
    return history.search(userId, query);
  }

  public RunHistoryDto.Trace trace(UUID userId, UUID runId) {
    return history.trace(userId, runId);
  }
}
