package com.jizza.engine.api.llm;

import com.jizza.engine.api.dto.UsageEntryDto;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Read-only reporting view over the LLM usage log (R49). */
public interface UsageStore {

  record ModelBreakdown(String model, long inputTokens, long outputTokens, BigDecimal costUnits) {}

  record Summary(Instant windowStart, Instant windowEnd,
                 long totalInputTokens, long totalOutputTokens,
                 BigDecimal totalCostUnits, boolean withinBudget,
                 BigDecimal budgetLimit, List<ModelBreakdown> breakdown) {}

  Summary summary(UUID userId);

  List<UsageEntryDto> recent(UUID userId, int limit);

  /** Records created in {@code [from, to)}, most recent first, at most {@code limit}. */
  default List<UsageEntryDto> between(UUID userId, Instant from, Instant to, int limit) {
    return recent(userId, limit).stream()
        .filter(e -> from == null || !e.createdAt().isBefore(from))
        .filter(e -> to == null || e.createdAt().isBefore(to))
        .toList();
  }
}
