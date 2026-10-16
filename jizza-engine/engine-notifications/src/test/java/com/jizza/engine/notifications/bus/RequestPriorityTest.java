package com.jizza.engine.notifications.bus;

import static org.assertj.core.api.Assertions.assertThat;

import com.jizza.engine.api.enums.NotificationPriority;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class RequestPriorityTest {

  @Test
  void anAgentCannotMakeSomethingUrgentWithoutADeadlineWithinADay() {
    assertThat(NotifyToolProvider.priority("urgent", null)).isEqualTo(NotificationPriority.NORMAL);
    assertThat(NotifyToolProvider.priority("urgent", Instant.now().plus(Duration.ofDays(3))))
        .isEqualTo(NotificationPriority.NORMAL);
    assertThat(NotifyToolProvider.priority("urgent", Instant.now().plus(Duration.ofHours(2))))
        .isEqualTo(NotificationPriority.URGENT);
  }

  @Test
  void anAgentCanAlwaysLowerIt() {
    assertThat(NotifyToolProvider.priority("low", Instant.now().plus(Duration.ofHours(1))))
        .isEqualTo(NotificationPriority.LOW);
    assertThat(NotifyToolProvider.priority("nonsense", null)).isEqualTo(NotificationPriority.NORMAL);
  }

  @Test
  void aRequestExpiresWithItsDeadline() {
    Duration left = NotifyToolProvider.expiresIn(Instant.now().plus(Duration.ofHours(3)));
    assertThat(left).isBetween(Duration.ofMinutes(179), Duration.ofMinutes(180));
    assertThat(NotifyToolProvider.expiresIn(Instant.now().minusSeconds(60)))
        .isEqualTo(Duration.ofMinutes(5));
    assertThat(NotifyToolProvider.expiresIn(null)).isNull();
    assertThat(NotifyToolProvider.deadline("2026-10-16T20:00:00+05:30")).isNotNull();
  }
}
