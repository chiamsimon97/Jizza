package com.jizza.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ProblemDetailTest {

  @Test
  void rendersRfc9457Fields() {
    var pd = new NotFoundException("Workflow", "abc").toProblemDetail();
    assertThat(pd.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
    assertThat(pd.getType().toString()).isEqualTo(JizzaException.TYPE_BASE + "not-found");
    assertThat(pd.getTitle()).isEqualTo("Not found");
    assertThat(pd.getDetail()).contains("Workflow abc");
  }

  @Test
  void stepUpErrorIsForbiddenWithItsOwnType() {
    var pd = new StepUpRequiredException().toProblemDetail();
    assertThat(pd.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
    assertThat(pd.getType().toString()).endsWith("step-up-required");
  }
}
