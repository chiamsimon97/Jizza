package com.jizza.engine.api.autonomy;

import java.time.LocalTime;
import java.util.Optional;
import java.util.UUID;

/** A user's guardrail settings that are not rules: when they do not want to be reached. */
public interface GuardrailSettingsStore {

  /**
   * The hours when only urgent approvals reach the user outside the app.
   *
   * @param start    when quiet time begins, or null for none
   * @param end      when it ends; may be earlier than start to wrap past midnight
   * @param timeZone an IANA zone such as {@code Asia/Singapore}
   */
  record QuietHours(LocalTime start, LocalTime end, String timeZone) {}

  Optional<QuietHours> quietHours(UUID userId);

  QuietHours saveQuietHours(UUID userId, QuietHours hours);
}
