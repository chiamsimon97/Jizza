package com.jizza.engine.api.memory;

/**
 * The user's memory consent.
 *
 * @param autoLearn     whether facts are picked up from conversations without being asked
 * @param retentionDays memories not used or updated for this long are forgotten; null keeps them
 */
public record MemorySettings(boolean autoLearn, Integer retentionDays) {

  public static MemorySettings defaults() { return new MemorySettings(true, null); }
}
