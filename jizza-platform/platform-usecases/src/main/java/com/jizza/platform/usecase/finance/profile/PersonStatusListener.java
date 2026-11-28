package com.jizza.platform.usecase.finance.profile;

import java.util.UUID;

/**
 * Told when a person of the shared household list changes status (Vita spec 5.13): Vita removes
 * a closed record's future dose rows and cancels its reminders.
 */
public interface PersonStatusListener {

  void statusChanged(UUID owner, UUID personId, String status);
}
