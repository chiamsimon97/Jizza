package com.jizza.engine.api.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Vita raised a medicine reminder for a user (spec 5.2): a time slot ({@code vita_slot}), the
 * grouped missed alert ({@code vita_missed}) or the daily check ({@code vita_daily_check}). The
 * phone app turns it into a push that carries only these ids; it fetches the rest over HTTPS.
 *
 * @param dueAt the slot's due time; null for the daily check
 */
public record VitaReminderDue(UUID userId, String type, UUID personId, Instant dueAt) {}
