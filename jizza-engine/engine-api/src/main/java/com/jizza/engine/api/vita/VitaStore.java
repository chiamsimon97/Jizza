package com.jizza.engine.api.vita;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Vita's typed storage: the configuration with its system defaults and the owner's overrides, and
 * the one profile row each person has. Every method takes the owner's user id.
 */
public interface VitaStore {

  /** A configuration row: a system default when {@code userId} is null, otherwise an override. */
  record ConfigRow(UUID id, UUID userId, String key, JsonNode value, LocalDate effectiveFrom,
                   LocalDate effectiveTo, String sourceUrl, LocalDate verifiedOn,
                   String verifiedBy) {}

  /** Every system row and every override of this owner. */
  List<ConfigRow> config(UUID userId);

  /** Sets the owner's override of one key for the version starting {@code effectiveFrom}. */
  void putOverride(UUID userId, String key, LocalDate effectiveFrom, JsonNode value);

  /** Removes every override of the key: "Reset to Vita default". Returns how many went. */
  int deleteOverrides(UUID userId, String key);

  /**
   * The person's profile row id, created empty when missing; safe to call concurrently. The person
   * must already be the owner's.
   */
  UUID ensureProfile(UUID userId, UUID personId, UUID createdBy);
}
