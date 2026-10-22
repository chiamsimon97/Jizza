package com.jizza.platform.api.rest;

import com.jizza.engine.api.util.AuthenticatedOnly;
import com.jizza.engine.api.dto.UsageEntryDto;
import com.jizza.engine.api.llm.UsageStore;
import com.jizza.engine.api.util.TimeBuckets;
import com.jizza.engine.security.session.CurrentUser;
import com.jizza.platform.usecase.usage.UsageExplorer;
import com.jizza.platform.usecase.usage.UsageUseCase;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** LLM token and cost visibility for the user (R49). */
@RestController
@AuthenticatedOnly
@RequestMapping("/api/usage")
public class UsageController {

  private final UsageUseCase usage;

  public UsageController(UsageUseCase usage) {
    this.usage = usage;
  }

  /** Rolling-window summary: totals, per-model breakdown, and budget status. */
  @GetMapping("/summary")
  public UsageStore.Summary summary() {
    return usage.summary(CurrentUser.userId());
  }

  /** Spend over a time range, grouped by one dimension and optionally a second. */
  @GetMapping("/explore")
  public UsageExplorer.Page explore(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
      @RequestParam(defaultValue = "MODEL") UsageExplorer.Dimension groupBy,
      @RequestParam(required = false) UsageExplorer.Dimension thenBy,
      @RequestParam(required = false) Set<String> model,
      @RequestParam(required = false) Set<String> area,
      @RequestParam(defaultValue = "100") int limit,
      @RequestParam(required = false) String tz) {
    return usage.explore(CurrentUser.userId(), new UsageExplorer.Query(from, to, groupBy, thenBy,
        model, area, limit, TimeBuckets.zoneOf(tz)));
  }

  /** Individual usage records, most-recent first. */
  @GetMapping("/entries")
  public List<UsageEntryDto> entries(@RequestParam(defaultValue = "100") int limit) {
    return usage.recent(CurrentUser.userId(), limit);
  }
}
