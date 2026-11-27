package com.jizza.platform.usecase.vita.sharing;

import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.finance.FinanceStore;
import com.jizza.engine.api.finance.FinanceStore.PersonRow;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Whose health rows a caller may reach. Every Vita read and write asks this first (spec 5.10).
 *
 * <p>Phase 0: only the caller's own rows, the people of the caller's own household list. Phase 2a
 * extends it with what Family members shared, section by section; until then another account's
 * person reads as missing, exactly like a person that does not exist. An HUF is a finance-only
 * entry and never has a health record.
 */
@Service
public class VitaScopeResolver {

  private final FinanceStore people;

  public VitaScopeResolver(FinanceStore people) {
    this.people = people;
  }

  /** The caller and the account that manages the rows being reached; equal until sharing exists. */
  public record VitaScope(UUID callerUserId, UUID ownerUserId, boolean canEdit) {
    public boolean own() { return callerUserId.equals(ownerUserId); }
  }

  /** One person's record as the caller may reach it. */
  public record PersonScope(VitaScope scope, PersonRow person) {
    public UUID owner() { return scope.ownerUserId(); }
    public UUID caller() { return scope.callerUserId(); }
    public UUID personId() { return person.id(); }
    public boolean open() { return "active".equals(person.status()); }

    /** Refuses a change to a closed record (spec 5.13); it stays readable and exportable. */
    public PersonScope requireOpen() {
      if (!open()) {
        throw new ValidationException("This record is closed"
            + (person.statusOn() == null ? "" : " (since " + person.statusOn() + ")")
            + " and can only be read or exported.");
      }
      if (!scope.canEdit()) throw new NotFoundException("Person", person.id());
      return this;
    }
  }

  /** The caller's own data. */
  public VitaScope own(UUID callerUserId) {
    if (callerUserId == null) throw new NotFoundException("Health record", "of nobody");
    return new VitaScope(callerUserId, callerUserId, true);
  }

  /** A person's record: refused (as not found) unless the person is in the caller's own list. */
  public PersonScope person(UUID callerUserId, UUID personId) {
    VitaScope scope = own(callerUserId);
    if (personId == null) throw new NotFoundException("Person", "none");
    PersonRow p = people.person(scope.ownerUserId(), personId)
        .filter(r -> !"huf".equals(r.kind()))
        .orElseThrow(() -> new NotFoundException("Person", personId));
    return new PersonScope(scope, p);
  }

  /** The caller's own "Me", created when missing (never a finance profile). */
  public PersonScope self(UUID callerUserId) {
    VitaScope scope = own(callerUserId);
    people.ensureSelf(scope.ownerUserId(), "Me");
    PersonRow me = people.people(scope.ownerUserId()).stream()
        .filter(p -> "self".equals(p.kind())).findFirst()
        .orElseThrow(() -> new NotFoundException("Person", "self"));
    return new PersonScope(scope, me);
  }

  /** The given person, or "Me" when none is named. */
  public PersonScope personOrSelf(UUID callerUserId, UUID personId) {
    return personId == null ? self(callerUserId) : person(callerUserId, personId);
  }
}
