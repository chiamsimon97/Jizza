package com.jizza.engine.agents.llm;

import com.jizza.engine.agents.config.AgentProperties;
import com.jizza.engine.api.dto.UsageEntryDto;
import com.jizza.engine.api.llm.UsageStore;
import com.jizza.engine.persistence.entity.LlmUsageEntity;
import com.jizza.engine.persistence.repository.LlmUsageRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

@Service
class DefaultUsageStore implements UsageStore {

  private final LlmUsageRepository repo;
  private final AgentProperties props;

  DefaultUsageStore(LlmUsageRepository repo, AgentProperties props) {
    this.repo = repo;
    this.props = props;
  }

  @Override
  public Summary summary(UUID userId) {
    Instant windowStart = Instant.now().minus(props.getBudget().getWindow());
    Instant windowEnd = Instant.now();

    List<LlmUsageEntity> rows =
        repo.findByUserIdAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(userId, windowStart);

    long totalInput = rows.stream().mapToLong(LlmUsageEntity::getInputTokens).sum();
    long totalOutput = rows.stream().mapToLong(LlmUsageEntity::getOutputTokens).sum();
    BigDecimal totalCost = rows.stream()
        .map(LlmUsageEntity::getCostUnits)
        .reduce(BigDecimal.ZERO, BigDecimal::add);

    Map<String, List<LlmUsageEntity>> byModel =
        rows.stream().collect(Collectors.groupingBy(LlmUsageEntity::getModel));
    List<ModelBreakdown> breakdown = byModel.entrySet().stream()
        .map(entry -> {
          List<LlmUsageEntity> group = entry.getValue();
          long in = group.stream().mapToLong(LlmUsageEntity::getInputTokens).sum();
          long out = group.stream().mapToLong(LlmUsageEntity::getOutputTokens).sum();
          BigDecimal cost = group.stream()
              .map(LlmUsageEntity::getCostUnits)
              .reduce(BigDecimal.ZERO, BigDecimal::add);
          return new ModelBreakdown(entry.getKey(), in, out, cost);
        })
        .sorted((a, b) -> b.costUnits().compareTo(a.costUnits()))
        .toList();

    double limit = props.getBudget().getPerWindowUnits();
    boolean withinBudget = limit <= 0 || totalCost.doubleValue() < limit;
    BigDecimal budgetLimit = limit <= 0 ? null : BigDecimal.valueOf(limit);

    return new Summary(windowStart, windowEnd, totalInput, totalOutput, totalCost, withinBudget,
        budgetLimit, breakdown);
  }

  @Override
  public List<UsageEntryDto> recent(UUID userId, int limit) {
    return repo.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, limit))
        .stream()
        .map(e -> new UsageEntryDto(e.getId(), e.getModel(), e.getPurpose(),
            e.getInputTokens(), e.getOutputTokens(), e.getCostUnits(), e.getCreatedAt()))
        .toList();
  }

  @Override
  public List<UsageEntryDto> between(UUID userId, Instant from, Instant to, int limit) {
    return repo.findWindow(userId, from == null ? Instant.EPOCH : from,
        to == null ? Instant.now().plusSeconds(60) : to, PageRequest.of(0, Math.max(1, limit)))
        .stream()
        .map(e -> new UsageEntryDto(e.getId(), e.getModel(), e.getPurpose(),
            e.getInputTokens(), e.getOutputTokens(), e.getCostUnits(), e.getCreatedAt()))
        .toList();
  }
}
