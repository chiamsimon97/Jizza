package com.jizza.engine.api.enums;

/**
 * memories.category -- what a remembered fact is about. The category decides the default set of
 * agent domains that may see the fact (see {@code jizza.memory.category-domains}).
 */
public enum MemoryCategory {
  PROFILE, PREFERENCE, RELATIONSHIP, FINANCE, HEALTH, PROFESSIONAL, PROJECT, TASK, GENERAL,
  /** A standing instruction from the user ("when X happens, do Y"). Only the user creates one. */
  INSTRUCTION
}
