package com.jizza.platform.usecase.finance.profile;

import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.error.ConflictException;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.finance.FinanceStore;
import com.jizza.engine.api.finance.FinanceStore.ConsentRow;
import com.jizza.engine.api.finance.FinanceStore.PersonRow;
import com.jizza.engine.api.util.Json;
import com.jizza.platform.usecase.finance.sharing.FinScopeResolver;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Consent notes for managed adults: an adult whose money another person records has agreed to it,
 * on a date, through a channel, optionally with evidence. Withdrawing a note schedules the
 * person's records for purging 30 days later (the purge job comes with Phase 5).
 */
@Service
public class ConsentService {

  public static final int PURGE_AFTER_DAYS = 30;
  static final Set<String> SCOPES = Set.of("view", "manage");

  private final FinanceStore store;
  private final FinScopeResolver scopes;
  private final AuditLog audit;

  public ConsentService(FinanceStore store, FinScopeResolver scopes, AuditLog audit) {
    this.store = store;
    this.scopes = scopes;
    this.audit = audit;
  }

  public List<ConsentRow> list(UUID callerUserId, UUID personId) {
    PersonRow person = person(callerUserId, personId);
    return store.consents(person.userId(), personId);
  }

  public ConsentRow record(UUID callerUserId, UUID personId, String scope, String channel,
                           UUID evidenceFileId) {
    PersonRow person = person(callerUserId, personId);
    if (!isManagedAdult(person, LocalDate.now(FinPersonUseCase.ZONE))) {
      throw new ValidationException(
          "Consent notes are only for adults you manage; children and HUFs do not need one.");
    }
    String s = scope == null ? "" : scope.strip().toLowerCase(java.util.Locale.ROOT);
    if (!SCOPES.contains(s)) throw new ValidationException("Scope must be 'view' or 'manage'.");
    if (channel != null && channel.length() > 40) {
      throw new ValidationException("Keep the channel under 40 characters.");
    }
    if (evidenceFileId != null && !store.ownsFile(person.userId(), evidenceFileId)) {
      throw new NotFoundException("File", evidenceFileId);
    }
    ConsentRow created = store.createConsent(person.userId(), personId, s,
        channel == null || channel.isBlank() ? null : channel.strip(), evidenceFileId);
    audit.record(person.userId(), ActorType.USER, callerUserId, "finance.consent.recorded",
        created.id().toString(), Json.obj().put("personId", personId.toString()).put("scope", s));
    return created;
  }

  public ConsentRow revoke(UUID callerUserId, UUID consentId) {
    var scope = scopes.own(callerUserId);
    ConsentRow current = store.consent(scope.ownerUserId(), consentId)
        .orElseThrow(() -> new NotFoundException("Consent note", consentId));
    if (current.withdrawnAt() != null) throw new ConflictException("This consent was already withdrawn.");
    LocalDate purgeAfter = LocalDate.now(FinPersonUseCase.ZONE).plusDays(PURGE_AFTER_DAYS);
    ConsentRow saved = store.withdrawConsent(scope.ownerUserId(), consentId, Instant.now(), purgeAfter);
    audit.record(scope.ownerUserId(), ActorType.USER, callerUserId, "finance.consent.withdrawn",
        consentId.toString(), Json.obj().put("personId", current.personId().toString()));
    return saved;
  }

  /** Someone else's adult whose money is recorded here: not "Me", not a minor, not an HUF. */
  static boolean isManagedAdult(PersonRow p, LocalDate today) {
    return !"self".equals(p.kind()) && "individual".equals(p.type())
        && !FinPersonUseCase.isMinorOn(p.dob(), today);
  }

  private PersonRow person(UUID callerUserId, UUID personId) {
    var scope = scopes.own(callerUserId);
    return store.person(scope.ownerUserId(), personId)
        .orElseThrow(() -> new NotFoundException("Person", personId));
  }
}
