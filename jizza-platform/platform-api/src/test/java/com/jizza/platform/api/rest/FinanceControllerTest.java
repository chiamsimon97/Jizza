package com.jizza.platform.api.rest;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class FinanceControllerTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(FinanceController.class);

  @Test
  void isAbsentUnlessTheSwitchIsOn() {
    runner.run(context -> assertThat(context).doesNotHaveBean(FinanceController.class));
    runner.withPropertyValues("jizza.finance.enabled=false")
        .run(context -> assertThat(context).doesNotHaveBean(FinanceController.class));
  }

  @Test
  void reportsEnabledWhenTheSwitchIsOn() {
    runner.withPropertyValues("jizza.finance.enabled=true").run(context -> {
      assertThat(context).hasSingleBean(FinanceController.class);
      assertThat(context.getBean(FinanceController.class).status().enabled()).isTrue();
    });
  }
}
