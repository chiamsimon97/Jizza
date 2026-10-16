package com.jizza.engine.notifications.escalation;

import com.jizza.engine.persistence.entity.GuardrailSettingsEntity;
import com.jizza.engine.persistence.repository.GuardrailSettingsRepository;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Whether it is the user's quiet time, when only urgent items reach them outside the app. */
@Component
public class QuietHours {

  private final GuardrailSettingsRepository settings;

  public QuietHours(GuardrailSettingsRepository settings) {
    this.settings = settings;
  }

  public boolean quietNow(UUID userId) {
    return settings.findById(userId).map(s -> quietAt(s, Instant.now())).orElse(false);
  }

  static boolean quietAt(GuardrailSettingsEntity s, Instant now) {
    if (s.getQuietHoursStart() == null || s.getQuietHoursEnd() == null) return false;
    ZoneId zone;
    try {
      zone = s.getTimeZone() == null ? ZoneId.of("UTC") : ZoneId.of(s.getTimeZone());
    } catch (RuntimeException e) {
      zone = ZoneId.of("UTC");
    }
    LocalTime time = now.atZone(zone).toLocalTime();
    LocalTime start = s.getQuietHoursStart();
    LocalTime end = s.getQuietHoursEnd();
    if (start.equals(end)) return false;
    // 22:00 to 07:00 wraps past midnight.
    return start.isBefore(end)
        ? !time.isBefore(start) && time.isBefore(end)
        : !time.isBefore(start) || time.isBefore(end);
  }
}
