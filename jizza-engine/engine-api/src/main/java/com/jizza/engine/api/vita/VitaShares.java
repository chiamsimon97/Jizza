package com.jizza.engine.api.vita;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Shares of one section of a person's health record with one Family member (spec 5.10). Unlike
 * every other Vita store this one is read from the grantee's side too, so each method says whose
 * rows it reads. A share is never deleted when it ends: it gets {@code endedAt} and a reason.
 */
public interface VitaShares {

  record Share(UUID id, UUID ownerUserId, UUID workspaceId, UUID personId, UUID granteeUserId, boolean asSelf,
               String section, String access, Instant grantedAt, Instant endedAt, String endedReason) {
    public boolean active() { return endedAt == null; }
    public boolean edit() { return "edit".equals(access); }
  }

  /** Active shares granted to this user, by anyone. */
  List<Share> activeFor(UUID granteeUserId);

  /** Active shares granted to this user for one person. */
  List<Share> activeFor(UUID granteeUserId, UUID personId);

  /** Active shares of one person's record, the manager's view. */
  List<Share> activeOf(UUID ownerUserId, UUID personId);

  /** Every share of one person's record, ended ones too, newest first: the access list. */
  List<Share> allOf(UUID ownerUserId, UUID personId);

  /** Every active share a manager granted. */
  List<Share> activeGrantedBy(UUID ownerUserId);

  Optional<Share> get(UUID ownerUserId, UUID shareId);

  /**
   * Grants one section, ending any active share of the same section to the same person first (as
   * {@code replaced}). Returns the new share.
   */
  Share grant(UUID ownerUserId, UUID workspaceId, UUID personId, UUID granteeUserId, boolean asSelf, String section,
              String access);

  /** Ends one share. False when it was not the manager's or had already ended. */
  boolean end(UUID ownerUserId, UUID shareId, String reason);

  /** Ends every active share of one person's record. */
  int endForPerson(UUID ownerUserId, UUID personId, String reason);

  /** Ends every active share between a manager and a grantee, in either direction. */
  int endBetween(UUID userA, UUID userB, String reason);

  /** Ends every active share made inside one Family that touches this user, as manager or grantee. */
  int endInWorkspace(UUID workspaceId, UUID userId, String reason);

  // ── Consent notes for health (fin_consent with domain 'health') ─────────────

  record Consent(UUID id, UUID personId, String scope, String channel, Instant grantedAt, Instant withdrawnAt) {
    public boolean open() { return withdrawnAt == null; }
  }

  /** The person's health consent notes, newest first. */
  List<Consent> healthConsents(UUID ownerUserId, UUID personId);

  /** Records a health consent note. */
  Consent recordHealthConsent(UUID ownerUserId, UUID personId, String scope, String channel);

  /** Withdraws one open health consent note. False when it was not found or already withdrawn. */
  boolean withdrawHealthConsent(UUID ownerUserId, UUID consentId);

  // ── Notes between the manager and helpers (vita_care_note) ────────────────

  record CareNote(UUID id, UUID personId, String text, UUID writtenByUserId, Instant writtenAt) {}

  /** The person's care notes, newest first, at most {@code limit}. */
  List<CareNote> careNotes(UUID ownerUserId, UUID personId, int limit);

  CareNote addCareNote(UUID ownerUserId, UUID personId, String text, UUID writtenByUserId);

  /** Removes the person's care notes (when their record is deleted). */
  int deleteCareNotes(UUID ownerUserId, UUID personId);
}
