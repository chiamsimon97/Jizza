package com.jizza.platform.api.rest.vita;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.util.AuthenticatedOnly;
import com.jizza.engine.api.util.StepUpRequired;
import com.jizza.engine.security.session.CurrentUser;
import com.jizza.engine.security.session.SessionService;
import com.jizza.platform.usecase.vita.records.VitaPersonRecordUseCase;
import com.jizza.platform.usecase.vita.sharing.VitaSharingUseCase;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sharing inside a Family (spec 5.10, 5.12, Phase 2a), behind the health switch: shares with the
 * four presets or a custom matrix, what is shared with me, the access list, consent notes, notes
 * between helpers, the doctors a share covers, and export or delete of one person's record. Only
 * the manager shares, exports or deletes; anything the caller may not reach reads as not found.
 */
@RestController
@AuthenticatedOnly
@RequestMapping("/api/vita")
@ConditionalOnProperty(name = "jizza.health.enabled", havingValue = "true")
public class VitaSharingController {

  private final VitaSharingUseCase sharing;
  private final VitaPersonRecordUseCase record;
  private final SessionService sessions;

  public VitaSharingController(VitaSharingUseCase sharing, VitaPersonRecordUseCase record, SessionService sessions) {
    this.sharing = sharing;
    this.record = record;
    this.sessions = sessions;
  }

  /**
   * {@code preset}: self, emergency, caregiver or everything_except_journal; {@code sections}
   * overrides it, section by section, with none, view or edit; {@code asSelf} links the member as
   * the person themself.
   */
  public record ShareBody(UUID personId, UUID granteeUserId, String preset, Map<String, String> sections,
                          Boolean asSelf) {}

  @GetMapping("/shares")
  public ObjectNode shares(@RequestParam(required = false) UUID personId) {
    return sharing.list(CurrentUser.userId(), personId);
  }

  /** Sets what one member may see of one record. Any Edit needs a fresh step-up sign-in. */
  @PostMapping("/shares")
  public ObjectNode share(@RequestBody ShareBody body) {
    boolean fresh = sessions.stepUpFresh(CurrentUser.sessionId());
    return sharing.grant(CurrentUser.userId(), new VitaSharingUseCase.GrantRequest(body.personId(),
        body.granteeUserId(), body.preset(), body.sections(), body.asSelf()), fresh);
  }

  @DeleteMapping("/shares/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void revoke(@PathVariable UUID id) {
    sharing.revoke(CurrentUser.userId(), id);
  }

  /** Ends everything one member was given on one record. */
  @DeleteMapping("/shares")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void revokeAll(@RequestParam UUID personId, @RequestParam UUID granteeUserId) {
    sharing.revokeAll(CurrentUser.userId(), personId, granteeUserId);
  }

  @GetMapping("/shared-with-me")
  public List<ObjectNode> sharedWithMe() {
    return sharing.sharedWithMe(CurrentUser.userId());
  }

  @GetMapping("/people/{personId}/access-list")
  public ObjectNode accessList(@PathVariable UUID personId) {
    return sharing.accessList(CurrentUser.userId(), personId);
  }

  // ── Consent notes ──────────────────────────────────────────────────────────

  public record ConsentBody(String scope, String channel) {}

  @GetMapping("/people/{personId}/consents")
  public List<ObjectNode> consents(@PathVariable UUID personId) {
    return sharing.consents(CurrentUser.userId(), personId);
  }

  @PostMapping("/people/{personId}/consents")
  @ResponseStatus(HttpStatus.CREATED)
  public ObjectNode recordConsent(@PathVariable UUID personId, @RequestBody ConsentBody body) {
    return sharing.recordConsent(CurrentUser.userId(), personId, body.scope(), body.channel());
  }

  /** Withdraws a note; with none left open, every helper's share of the record ends. */
  @DeleteMapping("/people/{personId}/consents/{id}")
  public ObjectNode withdrawConsent(@PathVariable UUID personId, @PathVariable UUID id) {
    return sharing.withdrawConsent(CurrentUser.userId(), personId, id);
  }

  // ── Care notes and doctors ─────────────────────────────────────────────────

  public record NoteBody(String text) {}

  @GetMapping("/people/{personId}/care-notes")
  public List<ObjectNode> careNotes(@PathVariable UUID personId) {
    return sharing.careNotes(CurrentUser.userId(), personId);
  }

  @PostMapping("/people/{personId}/care-notes")
  @ResponseStatus(HttpStatus.CREATED)
  public ObjectNode addCareNote(@PathVariable UUID personId, @RequestBody NoteBody body) {
    return sharing.addCareNote(CurrentUser.userId(), personId, body.text());
  }

  /** The doctors named on the visits or medicines the caller may see. */
  @GetMapping("/people/{personId}/doctors")
  public List<ObjectNode> doctors(@PathVariable UUID personId) {
    return sharing.doctors(CurrentUser.userId(), personId);
  }

  // ── One person's record ────────────────────────────────────────────────────

  @GetMapping("/people/{personId}/export")
  @StepUpRequired
  public ObjectNode export(@PathVariable UUID personId) {
    return record.export(CurrentUser.userId(), personId);
  }

  @DeleteMapping("/people/{personId}/record")
  @StepUpRequired
  public Map<String, Integer> delete(@PathVariable UUID personId) {
    return Map.of("deleted", record.delete(CurrentUser.userId(), personId));
  }
}
