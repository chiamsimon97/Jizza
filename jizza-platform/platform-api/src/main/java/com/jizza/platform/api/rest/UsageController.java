package com.jizza.platform.api.rest;

import com.jizza.engine.api.dto.UsageEntryDto;
import com.jizza.engine.api.llm.UsageStore;
import com.jizza.engine.security.session.CurrentUser;
import com.jizza.platform.usecase.usage.UsageUseCase;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** LLM token and cost visibility for the user (R49). */
@RestController
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

  /** Individual usage records, most-recent first. */
  @GetMapping("/entries")
  public List<UsageEntryDto> entries(@RequestParam(defaultValue = "100") int limit) {
    return usage.recent(CurrentUser.userId(), limit);
  }
}
