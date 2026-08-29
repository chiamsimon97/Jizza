package com.jizza.engine.agents.llm;

import com.jizza.engine.agents.config.AgentProperties;
import com.jizza.engine.api.llm.CostAccountant;
import com.jizza.engine.persistence.entity.LlmUsageEntity;
import com.jizza.engine.persistence.repository.LlmUsageRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Token accounting and the spend ceiling that stops a runaway loop costing real money (R49). */
@Service
public class DefaultCostAccountant implements CostAccountant {

  private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

  private final LlmUsageRepository usage;
  private final AgentProperties props;

  public DefaultCostAccountant(LlmUsageRepository usage, AgentProperties props) {
    this.usage = usage;
    this.props = props;
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void record(UUID userId, String model, int inputTokens, int outputTokens, String purpose) {
    LlmUsageEntity entity = new LlmUsageEntity();
    entity.setUserId(userId);
    entity.setModel(model == null ? "unknown" : model);
    entity.setPurpose(purpose);
    entity.setInputTokens(inputTokens);
    entity.setOutputTokens(outputTokens);
    entity.setCostUnits(cost(model, inputTokens, outputTokens));
    usage.save(entity);
  }

  @Override
  @Transactional(readOnly = true)
  public boolean withinBudget(UUID userId) {
    double ceiling = props.getBudget().getPerWindowUnits();
    if (ceiling <= 0) return true;
    return spendInWindow(userId) < ceiling;
  }

  @Override
  @Transactional(readOnly = true)
  public double spendInWindow(UUID userId) {
    Instant since = Instant.now().minus(props.getBudget().getWindow());
    BigDecimal spend = usage.spendSince(userId, since);
    return spend == null ? 0d : spend.doubleValue();
  }

  BigDecimal cost(String model, int inputTokens, int outputTokens) {
    AgentProperties.ModelPrice price = props.getPricing().get(model);
    if (price == null) return BigDecimal.ZERO;
    return BigDecimal.valueOf(inputTokens).multiply(BigDecimal.valueOf(price.getInputPerMillion()))
        .add(BigDecimal.valueOf(outputTokens).multiply(BigDecimal.valueOf(price.getOutputPerMillion())))
        .divide(MILLION, 6, RoundingMode.HALF_UP);
  }
}
