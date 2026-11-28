package com.jizza.platform.usecase.vita.sharing;

import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.finance.FinanceStore;
import com.jizza.engine.api.finance.FinanceStore.PersonRow;
import com.jizza.engine.api.vita.VitaShares;
import com.jizza.engine.api.vita.VitaShares.Share;
import com.jizza.engine.api.workspace.WorkspaceDirectory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Whose health rows a caller may reach. Every Vita read and write asks this first (spec 5.10).
 *
 * <ul>
 *   <li>Own rows: the people of the caller's own household list, with full access.
 *   <li>Another manager's person: only through an active share of that person and the section
 *       asked for, View or Edit, and only while both are active members of one Family and the
 *       record is open. Anything else reads as missing (404), exactly like a person that does not
 *       exist.
 * </ul>
 *
 * <p>A call that names no section reaches the caller's own people only. An HUF is a finance-only
 * entry and never has a health record.
 */
@Service
public class VitaScopeResolver {

  private final FinanceStore people;
  private final VitaShares shares;
  private final WorkspaceDirectory workspaces;

  public VitaScopeResolver(FinanceStore people, VitaShares shares, WorkspaceDirectory workspaces) {
    this.people = people;
    this.shares = shares;
    this.workspaces = workspaces;
  }

  /** The caller and the account that manages the rows being reached. */
  public record VitaScope(UUID callerUserId, UUID ownerUserId, boolean canEdit) {
    public boolean own() { return callerUserId.equals(ownerUserId); }
  }

  /**
   * What a shared caller holds on one person: the access per section, and whether they are linked
   * as the person themself. Null on {@link PersonScope} for the manager.
   */
  public record Grant(Map<VitaSection, String> sections, boolean asSelf) {
    public boolean canView(VitaSection s) {
      return sections.containsKey(s);
    }

    public boolean canEdit(VitaSection s) {
      return "edit".equals(sections.get(s));
    }
  }

  /** One person's record as the caller may reach it. */
  public record PersonScope(VitaScope scope, PersonRow person, Grant grant) {
    public PersonScope(VitaScope scope, PersonRow person) {
      this(scope, person, null);
    }

    public UUID owner() { return scope.ownerUserId(); }
    public UUID caller() { return scope.callerUserId(); }
    public UUID personId() { return person.id(); }
    public boolean open() { return "active".equals(person.status()); }
    /** True when the caller reaches this record through a share, not as its manager. */
    public boolean shared() { return grant != null; }
    public boolean manager() { return grant == null; }

    public boolean canView(VitaSection s) { return grant == null || grant.canView(s); }
    public boolean canEdit(VitaSection s) { return grant == null || grant.canEdit(s); }

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

    /** Only the manager deletes, shares, exports or closes (spec 5.10, "What Edit means"). */
    public PersonScope requireManager() {
      if (grant != null) throw new NotFoundException("Person", person.id());
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

  /**
   * A person's record for one section: the caller's own person, or another manager's through an
   * active share of that section (Edit when {@code write}). Everything else is not found.
   */
  public PersonScope person(UUID callerUserId, UUID personId, VitaSection section, boolean write) {
    VitaScope mine = own(callerUserId);
    if (personId == null) throw new NotFoundException("Person", "none");
    var own = people.person(mine.ownerUserId(), personId).filter(r -> !"huf".equals(r.kind()));
    if (own.isPresent()) return new PersonScope(mine, own.get());
    PersonScope shared = sharedScope(callerUserId, personId).orElseThrow(() -> new NotFoundException("Person", personId));
    if (section == null || !shared.grant().canView(section)) throw new NotFoundException("Person", personId);
    boolean edit = shared.grant().canEdit(section);
    if (write && !edit) throw new NotFoundException("Person", personId);
    return new PersonScope(new VitaScope(callerUserId, shared.owner(), edit), shared.person(), shared.grant());
  }

  /** Any section of a shared record (for the record's header, the access check of a list). */
  public java.util.Optional<PersonScope> sharedScope(UUID callerUserId, UUID personId) {
    List<Share> active = shares.activeFor(callerUserId, personId);
    if (active.isEmpty()) return java.util.Optional.empty();
    UUID owner = active.get(0).ownerUserId();
    if (active.stream().anyMatch(s -> !s.ownerUserId().equals(owner))) return java.util.Optional.empty();
    if (!workspaces.sameFamily(callerUserId, owner)) return java.util.Optional.empty();
    var row = people.person(owner, personId).filter(r -> !"huf".equals(r.kind()))
        .filter(r -> "active".equals(r.status()));
    if (row.isEmpty()) return java.util.Optional.empty();
    Map<VitaSection, String> sections = new EnumMap<>(VitaSection.class);
    boolean asSelf = false;
    for (Share s : active) {
      VitaSection sec = VitaSection.of(s.section());
      if (sec == null) continue;
      sections.put(sec, s.access());
      asSelf |= s.asSelf();
    }
    Grant g = new Grant(Collections.unmodifiableMap(sections), asSelf);
    return java.util.Optional.of(new PersonScope(new VitaScope(callerUserId, owner, false), row.get(), g));
  }

  /**
   * The accounts whose rows the caller may look a row up in by its id: their own, then each
   * manager who shares a record with them. The row's person is then checked section by section.
   */
  public List<UUID> reachableOwners(UUID callerUserId) {
    java.util.Set<UUID> out = new java.util.LinkedHashSet<>();
    out.add(own(callerUserId).ownerUserId());
    for (Share s : shares.activeFor(callerUserId)) out.add(s.ownerUserId());
    return List.copyOf(out);
  }

  /** Every record shared with the caller that they may reach now, the person themself first. */
  public List<PersonScope> sharedWithMe(UUID callerUserId) {
    Map<UUID, Boolean> seen = new LinkedHashMap<>();
    for (Share s : shares.activeFor(callerUserId)) seen.putIfAbsent(s.personId(), true);
    List<PersonScope> out = new ArrayList<>();
    for (UUID personId : seen.keySet()) sharedScope(callerUserId, personId).ifPresent(out::add);
    out.sort(java.util.Comparator.comparing((PersonScope p) -> !p.grant().asSelf())
        .thenComparing(p -> p.person().displayName().toLowerCase(java.util.Locale.ROOT)));
    return out;
  }

  /**
   * How a mark or an entry by {@code user} reads to the caller: "You", the person's own name when
   * they are linked to the record as themself, otherwise the account's email address.
   */
  public String nameOf(PersonScope p, UUID user) {
    if (user == null) return null;
    if (user.equals(p.caller())) return "You";
    boolean self = shares.activeOf(p.owner(), p.personId()).stream()
        .anyMatch(s -> s.asSelf() && s.granteeUserId().equals(user));
    if (self) return p.person().displayName();
    return workspaces.emailOf(user).orElse("Someone in your Family");
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

  /** The given person for one section (own or shared), or "Me" when none is named. */
  public PersonScope personOrSelf(UUID callerUserId, UUID personId, VitaSection section, boolean write) {
    return personId == null ? self(callerUserId) : person(callerUserId, personId, section, write);
  }
}
