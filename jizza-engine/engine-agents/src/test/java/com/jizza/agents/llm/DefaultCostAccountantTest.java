package com.jizza.agents.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jizza.agents.config.AgentProperties;
import com.jizza.persistence.repository.LlmUsageRepository;
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
}
