package com.jizza.engine.api.device;

import java.util.Locale;

/**
 * What the Jizza app on a person's own phone may do with their account. Each level includes the
 * ones before it. Shared machines enrolled with a token have no person behind them, so the phone
 * API refuses them whatever this says.
 */
public enum PhoneAccess {
  /** See runs, usage, activity, devices, agents, guardrails and what is waiting. */
  READ,
  /** Also answer approvals and questions, snooze them, and Pause all. */
  APPROVE,
  /** Also run workflows, cancel runs, and resume after Pause all. */
  FULL;

  public boolean allows(PhoneAccess needed) {
    return compareTo(needed) >= 0;
  }

  public String code() {
    return name().toLowerCase(Locale.ROOT);
  }

  /** Reads a stored or submitted value; unknown text falls back to the safe default. */
  public static PhoneAccess from(String value) {
    if (value == null) return APPROVE;
    try {
      return valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      return APPROVE;
    }
  }
}
