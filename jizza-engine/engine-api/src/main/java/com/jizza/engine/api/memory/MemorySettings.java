package com.jizza.engine.api.memory;

/**
 * The user's memory consent.
 *
 * @param autoLearn     whether facts are picked up from conversations without being asked
 * @param retentionDays memories not used or updated for this long are forgotten; null keeps them
 * @param review        whether the nightly memory review suggests changes; null on an update
 *                      leaves it as it is
 */
public record MemorySettings(boolean autoLearn, Integer retentionDays, Boolean review) {

  public MemorySettings(boolean autoLearn, Integer retentionDays) {
    this(autoLearn, retentionDays, null);
  }

  public static MemorySettings defaults() { return new MemorySettings(true, null, true); }

  /** Whether the review runs; it is on unless the user switched it off. */
  public boolean reviewOn() { return review == null || review; }
}
