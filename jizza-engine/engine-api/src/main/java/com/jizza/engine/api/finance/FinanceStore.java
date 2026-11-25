package com.jizza.engine.api.finance;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Where Kai Portfolio's rows are kept (Phase 0: profile, people, consent notes, configuration).
 *
 * <p>Every method takes the owner's user id and touches only that owner's rows (plus the shared
 * system rows of the configuration); a row of another owner reads as missing. Deciding whose rows
 * a caller may reach is not done here but in the finance use cases' scope resolver.
 */
public interface FinanceStore {

  record ProfileRow(UUID id, UUID userId, String baseCurrency, JsonNode settings, long version) {}

  record PersonRow(UUID id, UUID userId, String displayName, String kind, String type,
                   LocalDate dob, boolean earning, boolean dependent, String status,
                   int sortOrder, UUID handedOverToUserId) {}

  /** What a person's add or edit form carries. */
  record PersonFields(String displayName, String kind, String type, LocalDate dob,
                      boolean earning, boolean dependent) {}

  record ConsentRow(UUID id, UUID personId, String scope, Instant grantedAt, Instant withdrawnAt,
                    String channel, UUID evidenceFileId, LocalDate purgeAfter) {}

  /** A configuration row: a system default when {@code userId} is null, otherwise an override. */
  record ConfigRow(UUID id, UUID userId, String key, JsonNode value, LocalDate effectiveFrom,
                   LocalDate effectiveTo, String sourceUrl, LocalDate verifiedOn,
                   String verifiedBy) {}

  /** The owner's profile and their "Me" person, created when missing; safe to call concurrently. */
  ProfileRow ensureProfile(UUID userId, String selfName);

  Optional<ProfileRow> profile(UUID userId);

  ProfileRow saveSettings(UUID userId, JsonNode settings);

  List<PersonRow> people(UUID userId);

  Optional<PersonRow> person(UUID userId, UUID personId);

  PersonRow createPerson(UUID userId, PersonFields fields);

  PersonRow updatePerson(UUID userId, UUID personId, PersonFields fields);

  PersonRow setPersonStatus(UUID userId, UUID personId, String status);

  List<ConsentRow> consents(UUID userId, UUID personId);

  List<ConsentRow> allConsents(UUID userId);

  Optional<ConsentRow> consent(UUID userId, UUID consentId);

  ConsentRow createConsent(UUID userId, UUID personId, String scope, String channel,
                           UUID evidenceFileId);

  ConsentRow withdrawConsent(UUID userId, UUID consentId, Instant at, LocalDate purgeAfter);

  /** Whether the stored file is the owner's, so a consent note cannot point at someone else's. */
  boolean ownsFile(UUID userId, UUID fileId);

  /** Every system row and every override of this owner. */
  List<ConfigRow> config(UUID userId);

  /** Sets the owner's override of one key for the version starting {@code effectiveFrom}. */
  void putOverride(UUID userId, String key, LocalDate effectiveFrom, JsonNode value);

  /** Removes every override of the key: "Reset to Kai default". Returns how many went. */
  int deleteOverrides(UUID userId, String key);
}
