package com.jizza.engine.api.enums;

/** What happened when something was remembered. */
public enum MemoryWriteOutcome {
  /** A new fact. */
  CREATED,
  /** Already known; the existing fact was reinforced instead of storing a copy. */
  DUPLICATE,
  /** A newer fact with the same key replaced an older one. */
  SUPERSEDED_PREVIOUS,
  /** Conflicts with a confirmed fact; stored as pending until the user confirms it. */
  PENDING_CONFLICT
}
