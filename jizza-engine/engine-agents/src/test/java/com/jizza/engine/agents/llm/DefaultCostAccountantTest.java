package com.jizza.engine.agents.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jizza.engine.agents.config.AgentProperties;
import com.jizza.engine.persistence.repository.LlmUsageRepository;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DefaultCostAccountantTest {

  private LlmUsageRepository usage;
  private AgentProperties props;
  private DefaultCostAccountant accountant;

  @BeforeEach
  void setUp() {
    usage = mock(LlmUsageRepository.class);
    props = new AgentProperties();
    AgentProperties.ModelPrice price = new AgentProperties.ModelPrice();
    price.setInputPerMillion(3.0);
    price.setOutputPerMillion(15.0);
    props.getPricing().put("priced-model", price);
    accountant = new DefaultCostAccountant(usage, props);
  }

  @Test
  void pricesAConfiguredModel() {
    assertThat(accountant.cost("priced-model", 1_000_000, 0)).isEqualByComparingTo("3.0");
    assertThat(accountant.cost("priced-model", 0, 100_000)).isEqualByComparingTo("1.5");
  }

  @Test
  void pricesCacheReadsAndWritesAtTheirOwnRates() {
    // 1M input tokens of which 800k read from the cache and 100k written to it: 100k at the full
    // $3, 800k at a tenth of it, 100k at a quarter more than it.
    assertThat(accountant.cost("priced-model", 1_000_000, 0, 800_000, 100_000))
        .isEqualByComparingTo(new BigDecimal("0.3").add(new BigDecimal("0.24"))
            .add(new BigDecimal("0.375")));
  }

  @Test
  void aCallWithoutCacheFiguresCostsWhatItAlwaysDid() {
    assertThat(accountant.cost("priced-model", 1_000_000, 100_000, 0, 0))
        .isEqualByComparingTo(accountant.cost("priced-model", 1_000_000, 100_000));
  }

  @Test
  void treatsAnUnpricedModelAsZeroRatherThanGuessing() {
    assertThat(accountant.cost("some-new-model", 1_000_000, 1_000_000))
        .isEqualByComparingTo(BigDecimal.ZERO);
  }

  @Test
  void enforcesTheSpendCeiling() {
    props.getBudget().setPerWindowUnits(2.0);
    when(usage.spendSince(any(), any())).thenReturn(new BigDecimal("1.50"));
    assertThat(accountant.withinBudget(UUID.randomUUID())).isTrue();

    when(usage.spendSince(any(), any())).thenReturn(new BigDecimal("2.00"));
    assertThat(accountant.withinBudget(UUID.randomUUID())).isFalse();
  }

  @Test
  void aZeroCeilingMeansNoCeiling() {
    props.getBudget().setPerWindowUnits(0);
    when(usage.spendSince(any(), any())).thenReturn(new BigDecimal("999"));
    assertThat(accountant.withinBudget(UUID.randomUUID())).isTrue();
  }

  @Test
  void aModelOnThePersonsOwnKeyIsPricedFromTheirCatalogAndNotChargedToThePlatform() {
    UUID user = UUID.randomUUID();
    com.jizza.engine.api.llm.ModelCatalog catalog = mock(com.jizza.engine.api.llm.ModelCatalog.class);
    when(catalog.find(user, "openai:gpt-5.1")).thenReturn(java.util.Optional.of(
        new com.jizza.engine.api.llm.ModelCatalog.ModelInfo("openai:gpt-5.1", "openai", "GPT-5.1", null,
            true, true, true, true, null, new BigDecimal("1.25"), new BigDecimal("10"))));
    @SuppressWarnings("unchecked")
    org.springframework.beans.factory.ObjectProvider<com.jizza.engine.api.llm.ModelCatalog> provider =
        mock(org.springframework.beans.factory.ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(catalog);
    accountant.setCatalog(provider);
    when(usage.save(any())).thenAnswer(inv -> inv.getArgument(0));

    accountant.record(user, "openai:gpt-5.1", 1_000_000, 100_000, 0, 0, "agent:jizza");

    var saved = org.mockito.ArgumentCaptor.forClass(com.jizza.engine.persistence.entity.LlmUsageEntity.class);
    org.mockito.Mockito.verify(usage).save(saved.capture());
    assertThat(saved.getValue().getPaidBy()).isEqualTo("own");
    assertThat(saved.getValue().getCostUnits()).isEqualByComparingTo("2.25");
  }
}
