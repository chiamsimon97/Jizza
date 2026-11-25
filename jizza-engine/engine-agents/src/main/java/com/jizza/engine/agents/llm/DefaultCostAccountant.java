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
  private org.springframework.beans.factory.ObjectProvider<com.jizza.engine.api.llm.ModelCatalog> catalog;

  public DefaultCostAccountant(LlmUsageRepository usage, AgentProperties props) {
    this.usage = usage;
    this.props = props;
  }

  /** Prices for models on a person's own key; without it they are recorded at no cost. */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setCatalog(org.springframework.beans.factory.ObjectProvider<com.jizza.engine.api.llm.ModelCatalog> catalog) {
    this.catalog = catalog;
  }

  @Override
  public double costOf(UUID userId, String model, com.jizza.engine.api.llm.LlmResponse r) {
    return cost(userId, model, r.inputTokens(), r.outputTokens(), r.cacheReadTokens(),
        r.cacheWriteTokens()).doubleValue();
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void record(UUID userId, String model, int inputTokens, int outputTokens, String purpose) {
    record(userId, model, inputTokens, outputTokens, 0, 0, purpose);
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void record(UUID userId, String model, int inputTokens, int outputTokens,
                     int cacheReadTokens, int cacheWriteTokens, String purpose) {
    LlmUsageEntity entity = new LlmUsageEntity();
    entity.setUserId(userId);
    entity.setModel(model == null ? "unknown" : model);
    entity.setPurpose(purpose);
    entity.setInputTokens(inputTokens);
    entity.setOutputTokens(outputTokens);
    entity.setCacheReadTokens(cacheReadTokens);
    entity.setCacheWriteTokens(cacheWriteTokens);
    entity.setCostUnits(cost(userId, model, inputTokens, outputTokens, cacheReadTokens, cacheWriteTokens));
    // A model on the person's own key is billed to them, not to the platform's ceiling.
    entity.setPaidBy(com.jizza.engine.api.llm.ModelRefs.ownKey(model)
        ? LlmUsageEntity.OWN : LlmUsageEntity.PLATFORM);
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

  @Override
  @Transactional(readOnly = true)
  public double spendSince(UUID userId, String purpose, Instant since) {
    BigDecimal spend = usage.spendSinceForPurpose(userId, purpose, since);
    return spend == null ? 0d : spend.doubleValue();
  }

  /** A platform model is priced from configuration; one on the person's own key from their catalog. */
  BigDecimal cost(UUID userId, String model, int inputTokens, int outputTokens, int cacheReadTokens,
                  int cacheWriteTokens) {
    if (!com.jizza.engine.api.llm.ModelRefs.ownKey(model)) {
      return cost(model, inputTokens, outputTokens, cacheReadTokens, cacheWriteTokens);
    }
    var known = catalog == null ? null : catalog.getIfAvailable();
    if (known == null) return BigDecimal.ZERO;
    var info = known.find(userId, model).orElse(null);
    if (info == null || info.inputPerMillion() == null || info.outputPerMillion() == null) {
      return BigDecimal.ZERO;
    }
    // Cached input is priced at the full input rate here: providers differ in how they discount it.
    return BigDecimal.valueOf(inputTokens).multiply(info.inputPerMillion())
        .add(BigDecimal.valueOf(outputTokens).multiply(info.outputPerMillion()))
        .divide(MILLION, 6, RoundingMode.HALF_UP);
  }

  BigDecimal cost(String model, int inputTokens, int outputTokens) {
    return cost(model, inputTokens, outputTokens, 0, 0);
  }

  /**
   * {@code inputTokens} counts every input token; the cached part of it is priced at the cache
   * rates (a read is a fraction of the input price, a write a little more than it).
   */
  BigDecimal cost(String model, int inputTokens, int outputTokens, int cacheReadTokens,
                  int cacheWriteTokens) {
    AgentProperties.ModelPrice price = props.getPricing().get(model);
    if (price == null) return BigDecimal.ZERO;
    BigDecimal input = BigDecimal.valueOf(price.getInputPerMillion());
    int uncached = Math.max(0, inputTokens - cacheReadTokens - cacheWriteTokens);
    return BigDecimal.valueOf(uncached).multiply(input)
        .add(BigDecimal.valueOf(cacheReadTokens).multiply(input)
            .multiply(BigDecimal.valueOf(price.getCacheReadMultiplier())))
        .add(BigDecimal.valueOf(cacheWriteTokens).multiply(input)
            .multiply(BigDecimal.valueOf(price.getCacheWriteMultiplier())))
        .add(BigDecimal.valueOf(outputTokens).multiply(BigDecimal.valueOf(price.getOutputPerMillion())))
        .divide(MILLION, 6, RoundingMode.HALF_UP);
  }
}
