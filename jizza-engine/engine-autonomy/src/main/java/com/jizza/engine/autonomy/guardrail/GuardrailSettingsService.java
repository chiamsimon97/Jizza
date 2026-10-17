package com.jizza.engine.autonomy.guardrail;

import com.jizza.engine.api.autonomy.GuardrailSettingsStore;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.persistence.entity.GuardrailSettingsEntity;
import com.jizza.engine.persistence.repository.GuardrailSettingsRepository;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Storage for a user's quiet hours. */
@Service
public class GuardrailSettingsService implements GuardrailSettingsStore {

  private final GuardrailSettingsRepository settings;

  public GuardrailSettingsService(GuardrailSettingsRepository settings) {
    this.settings = settings;
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<QuietHours> quietHours(UUID userId) {
    return settings.findById(userId).map(s -> new QuietHours(s.getQuietHoursStart(),
        s.getQuietHoursEnd(), s.getTimeZone()));
  }

  @Override
  @Transactional
  public QuietHours saveQuietHours(UUID userId, QuietHours hours) {
    if ((hours.start() == null) != (hours.end() == null)) {
      throw new ValidationException("Quiet hours need both a start and an end, or neither");
    }
    String zone = hours.timeZone() == null || hours.timeZone().isBlank() ? "UTC"
        : hours.timeZone().trim();
    try {
      ZoneId.of(zone);
    } catch (DateTimeException e) {
      throw new ValidationException("Unknown time zone " + zone);
    }
    GuardrailSettingsEntity entity = settings.findById(userId).orElseGet(() -> {
      GuardrailSettingsEntity created = new GuardrailSettingsEntity();
      created.setUserId(userId);
      return created;
    });
    entity.setQuietHoursStart(hours.start());
    entity.setQuietHoursEnd(hours.end());
    entity.setTimeZone(zone);
    settings.save(entity);
    return new QuietHours(hours.start(), hours.end(), zone);
  }
}
