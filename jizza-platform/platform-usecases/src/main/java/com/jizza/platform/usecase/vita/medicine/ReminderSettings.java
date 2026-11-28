package com.jizza.platform.usecase.vita.medicine;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.platform.usecase.vita.config.VitaConfigService;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The owner's reminder settings (spec 5.2, config keys under {@code reminder.}): product choices
 * seeded by V68, each one the user can change in Settings. The fallbacks below are the same
 * product choices, used only if a row is missing.
 */
public record ReminderSettings(int missedAfterMinutes, int snoozeMinutes, LocalTime dailyCheckTime,
                               boolean showMedicineNames, Map<String, Integer> leadDays) {

  public static ReminderSettings of(VitaConfigService config, UUID owner) {
    Map<String, JsonNode> v = new HashMap<>();
    config.list(owner).stream().filter(i -> i.key().startsWith("reminder.") && i.value() != null)
        .forEach(i -> v.put(i.key(), i.value()));
    int missed = positive(v.get("reminder.missed_after_minutes"), "minutes", 60);
    int snooze = positive(v.get("reminder.snooze_minutes"), "minutes", 15);
    LocalTime daily = LocalTime.of(20, 0);
    JsonNode t = v.get("reminder.daily_check_time");
    if (t != null && t.path("time").isTextual()) {
      try {
        daily = LocalTime.parse(t.path("time").asText());
      } catch (RuntimeException ignored) {
        // keep the fallback
      }
    }
    boolean names = v.containsKey("reminder.show_medicine_names")
        && v.get("reminder.show_medicine_names").path("enabled").asBoolean(false);
    Map<String, Integer> lead = new HashMap<>(Map.of("visit", 1, "vaccination", 7, "policy_renewal", 30, "check_due", 14));
    JsonNode l = v.get("reminder.lead_days");
    if (l != null && l.isObject()) {
      l.fields().forEachRemaining(e -> {
        if (e.getValue().canConvertToInt() && e.getValue().asInt() >= 0) lead.put(e.getKey(), e.getValue().asInt());
      });
    }
    return new ReminderSettings(missed, snooze, daily, names, Map.copyOf(lead));
  }

  private static int positive(JsonNode n, String field, int fallback) {
    if (n == null || !n.path(field).canConvertToInt()) return fallback;
    int x = n.path(field).asInt();
    return x > 0 ? x : fallback;
  }

  public java.time.Duration missedAfter() {
    return java.time.Duration.ofMinutes(missedAfterMinutes);
  }
}
