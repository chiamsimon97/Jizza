package com.jizza.platform.usecase.finance.profile;

import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.finance.FinanceStore;
import com.jizza.engine.api.finance.FinanceStore.PersonFields;
import com.jizza.engine.api.finance.FinanceStore.PersonRow;
import com.jizza.platform.usecase.finance.sharing.FinScopeResolver;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * "People you manage" (7.22): the user plus anyone without a login whose money they look after.
 * Family members with their own login are not added here; they join a Family (Phase 5).
 *
 * <p>The same list serves Vita (the household people list Kai and Vita share, Vita spec 4.1), so it
 * has no finance-switch condition and creates only "Me", never a finance profile.
 */
@Service
public class FinPersonUseCase {

  static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
  static final Set<String> MANAGED_KINDS = Set.of("spouse", "child", "parent", "huf", "other");
  static final Set<String> TYPES = Set.of("individual", "minor", "huf");

  private final FinanceStore store;
  private final FinScopeResolver scopes;
  private final AuditLog audit;

  public FinPersonUseCase(FinanceStore store, FinScopeResolver scopes, AuditLog audit) {
    this.store = store;
    this.scopes = scopes;
    this.audit = audit;
  }

  /** A form as sent; {@code kind} and {@code type} are lowercase words. */
  public record PersonInput(String displayName, String kind, String type, LocalDate dob,
                            Boolean earning, Boolean dependent) {}

  public List<PersonRow> list(UUID callerUserId) {
    var scope = scopes.own(callerUserId);
    store.ensureSelf(scope.ownerUserId(), FinProfileUseCase.SELF_NAME);
    return store.people(scope.ownerUserId());
  }

  public PersonRow get(UUID callerUserId, UUID personId) {
    var scope = scopes.own(callerUserId);
    return store.person(scope.ownerUserId(), personId)
        .orElseThrow(() -> new NotFoundException("Person", personId));
  }

  public PersonRow create(UUID callerUserId, PersonInput in) {
    var scope = scopes.own(callerUserId);
    store.ensureSelf(scope.ownerUserId(), FinProfileUseCase.SELF_NAME);
    PersonFields fields = validate(in, null);
    PersonRow created = store.createPerson(scope.ownerUserId(), fields);
    audit.record(scope.ownerUserId(), ActorType.USER, callerUserId, "finance.person.added",
        created.id().toString(), null);
    return created;
  }

  /** Edits the fields sent; "Me" keeps its kind and type. */
  public PersonRow update(UUID callerUserId, UUID personId, PersonInput in) {
    PersonRow current = get(callerUserId, personId);
    if (!"active".equals(current.status())) {
      throw new ValidationException("This person is archived; restore them before editing.");
    }
    PersonInput merged = new PersonInput(
        in.displayName() != null ? in.displayName() : current.displayName(),
        in.kind() != null ? in.kind() : current.kind(),
        in.type() != null ? in.type() : current.type(),
        in.dob() != null ? in.dob() : current.dob(),
        in.earning() != null ? in.earning() : current.earning(),
        in.dependent() != null ? in.dependent() : current.dependent());
    PersonFields fields = validate(merged, current);
    PersonRow saved = store.updatePerson(current.userId(), personId, fields);
    audit.record(current.userId(), ActorType.USER, callerUserId, "finance.person.updated",
        personId.toString(), null);
    return saved;
  }

  /** Delete archives: the records stay for history and the person leaves the switchers. */
  public PersonRow archive(UUID callerUserId, UUID personId) {
    PersonRow current = get(callerUserId, personId);
    if ("self".equals(current.kind())) {
      throw new ValidationException("You cannot remove yourself from your own Portfolio.");
    }
    PersonRow saved = store.setPersonStatus(current.userId(), personId, "archived");
    audit.record(current.userId(), ActorType.USER, callerUserId, "finance.person.archived",
        personId.toString(), null);
    return saved;
  }

  /** The statuses a person can be given here; handing a record over is not built yet. */
  public static final Set<String> STATUSES = Set.of("active", "archived", "deceased");

  /**
   * Changes a person's status with the date it took effect: archived or deceased closes their
   * records, active reopens them. "Me" always stays active; the date cannot be in the future.
   */
  public PersonRow setStatus(UUID callerUserId, UUID personId, String status, LocalDate on) {
    PersonRow current = get(callerUserId, personId);
    String s = status == null ? "" : status.strip().toLowerCase(java.util.Locale.ROOT);
    if (!STATUSES.contains(s)) {
      throw new ValidationException("status must be one of active, archived, deceased.");
    }
    if ("self".equals(current.kind())) {
      throw new ValidationException("Your own entry always stays active.");
    }
    LocalDate today = LocalDate.now(ZONE);
    LocalDate day = on == null ? today : on;
    if (day.isAfter(today)) throw new ValidationException("The date is in the future.");
    PersonRow saved = store.setPersonStatus(current.userId(), personId, s, "active".equals(s) ? null : day);
    audit.record(current.userId(), ActorType.USER, callerUserId, "person.status_changed",
        personId.toString(), null);
    return saved;
  }

  static boolean isMinorOn(LocalDate dob, LocalDate day) {
    return dob != null && dob.plusYears(18).isAfter(day);
  }

  private static PersonFields validate(PersonInput in, PersonRow current) {
    String name = in.displayName() == null ? "" : in.displayName().strip();
    if (name.isEmpty()) throw new ValidationException("Give the person a name.");
    if (name.length() > 80) throw new ValidationException("Keep the name under 80 characters.");

    boolean self = current != null && "self".equals(current.kind());
    String kind = in.kind() == null ? null : in.kind().strip().toLowerCase(java.util.Locale.ROOT);
    String type = in.type() == null ? "individual" : in.type().strip().toLowerCase(java.util.Locale.ROOT);
    if (self) {
      if (!"self".equals(kind) || !"individual".equals(type)) {
        throw new ValidationException("Your own entry stays 'self' and 'individual'.");
      }
    } else if (kind == null || !MANAGED_KINDS.contains(kind)) {
      throw new ValidationException("Relationship must be one of " + MANAGED_KINDS + ".");
    }
    if (!TYPES.contains(type)) throw new ValidationException("Type must be one of " + TYPES + ".");
    if ("huf".equals(kind) != "huf".equals(type)) {
      throw new ValidationException("An HUF is recorded with relationship 'huf' and type 'huf'.");
    }

    LocalDate today = LocalDate.now(ZONE);
    if (in.dob() != null && in.dob().isAfter(today)) {
      throw new ValidationException("The date of birth is in the future.");
    }
    if ("minor".equals(type)) {
      if (in.dob() == null) throw new ValidationException("A minor needs a date of birth.");
      if (!isMinorOn(in.dob(), today)) {
        throw new ValidationException("This person is 18 or older; record them as an individual.");
      }
    }
    return new PersonFields(name, self ? "self" : kind, type, in.dob(),
        Boolean.TRUE.equals(in.earning()), Boolean.TRUE.equals(in.dependent()));
  }
}
