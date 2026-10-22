package com.jizza.platform.api.rest;

import com.jizza.engine.api.dto.RunHistoryDto;
import com.jizza.engine.api.enums.RunStatus;
import com.jizza.engine.api.util.AuthenticatedOnly;
import com.jizza.engine.api.util.TimeBuckets;
import com.jizza.engine.security.session.CurrentUser;
import com.jizza.platform.usecase.workflow.RunHistoryUseCase;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Workflow run history: every execution, and each one's steps with inputs, outputs and errors. */
@RestController
@AuthenticatedOnly
@RequestMapping("/api/runs")
public class RunHistoryController {

  private final RunHistoryUseCase history;

  public RunHistoryController(RunHistoryUseCase history) {
    this.history = history;
  }

  @GetMapping
  public RunHistoryDto.Page search(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
      @RequestParam(required = false) UUID workflowId,
      @RequestParam(required = false) Set<RunStatus> status,
      @RequestParam(required = false) Set<String> trigger,
      @RequestParam(required = false) String q,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset,
      @RequestParam(required = false) String tz) {
    return history.search(CurrentUser.userId(), new RunHistoryDto.Query(from, to, workflowId,
        status, trigger, q, limit, offset, TimeBuckets.zoneOf(tz)));
  }

  @GetMapping("/{id}/trace")
  public RunHistoryDto.Trace trace(@PathVariable UUID id) {
    return history.trace(CurrentUser.userId(), id);
  }
}
