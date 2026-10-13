package com.jizza.engine.connectors.tools.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jizza.engine.api.error.ValidationException;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ServiceVariablesTest {

  @Test
  void replacesReferencesAndLeavesOtherBracesAlone() {
    assertThat(ServiceVariables.render("/orgs/{{ var.org }}/items/{id}", Map.of("org", "a$1\\b")))
        .isEqualTo("/orgs/a$1\\b/items/{id}");
    assertThat(ServiceVariables.render(null, Map.of())).isNull();
  }

  @Test
  void anUndefinedVariableIsAnError() {
    assertThatThrownBy(() -> ServiceVariables.render("{{var.missing}}", Map.of()))
        .isInstanceOf(ValidationException.class).hasMessageContaining("missing");
  }

  @Test
  void triggersAreDueWhenTheirIntervalHasPassed() {
    var trigger = new com.jizza.engine.persistence.entity.ServiceTriggerEntity();
    trigger.setPollIntervalMinutes(15);
    var now = java.time.Instant.now();
    assertThat(TriggerPoller.isDue(trigger, now)).isTrue();
    trigger.setLastPolledAt(now.minusSeconds(60));
    assertThat(TriggerPoller.isDue(trigger, now)).isFalse();
    trigger.setLastPolledAt(now.minusSeconds(15 * 60));
    assertThat(TriggerPoller.isDue(trigger, now)).isTrue();
  }
}
