package com.jizza.engine.notifications.escalation;

import static org.assertj.core.api.Assertions.assertThat;

import com.jizza.engine.persistence.entity.GuardrailSettingsEntity;
import java.time.Instant;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;

class QuietHoursTest {

  private static GuardrailSettingsEntity settings(String from, String to, String zone) {
    GuardrailSettingsEntity s = new GuardrailSettingsEntity();
    s.setQuietHoursStart(from == null ? null : LocalTime.parse(from));
    s.setQuietHoursEnd(to == null ? null : LocalTime.parse(to));
    s.setTimeZone(zone);
    return s;
  }

  @Test
  void aWindowPastMidnightIsQuietOnBothSidesOfIt() {
    var night = settings("22:00", "07:00", "Asia/Kolkata");
    // 23:30 and 02:00 in Kolkata are quiet; 12:00 is not.
    assertThat(QuietHours.quietAt(night, Instant.parse("2026-10-16T18:00:00Z"))).isTrue();
    assertThat(QuietHours.quietAt(night, Instant.parse("2026-10-16T20:30:00Z"))).isTrue();
    assertThat(QuietHours.quietAt(night, Instant.parse("2026-10-16T06:30:00Z"))).isFalse();
  }

  @Test
  void noWindowMeansNeverQuiet() {
    assertThat(QuietHours.quietAt(settings(null, null, null), Instant.now())).isFalse();
    assertThat(QuietHours.quietAt(settings("09:00", "09:00", "UTC"), Instant.now())).isFalse();
  }
}
