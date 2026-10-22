package com.jizza.engine.api.workflow;

import com.jizza.engine.api.dto.RunHistoryDto;
import java.util.UUID;

/**
 * The read side of workflow runs: every execution a person's workflows made, with what each step
 * received, produced, and why it failed. The engine owns writing runs; this only looks at them.
 */
public interface RunHistory {

  /** Runs matching the query, newest first, plus the stats and timeline for the whole match. */
  RunHistoryDto.Page search(UUID userId, RunHistoryDto.Query query);

  /** One run step by step, laid over the workflow graph it ran. */
  RunHistoryDto.Trace trace(UUID userId, UUID runId);
}
