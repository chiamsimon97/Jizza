package com.jizza.platform.usecase.vita.sharing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.dto.NotificationDto;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.error.ConflictException;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.StepUpRequiredException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.notification.NotificationBus;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.vita.VitaRecords;
import com.jizza.engine.api.vita.VitaShares;
import com.jizza.engine.api.vita.VitaShares.Share;
import com.jizza.engine.api.workspace.WorkspaceDirectory;
import com.jizza.platform.usecase.vita.records.VitaRecordsUseCase;
import com.jizza.platform.usecase.vita.records.VitaTable;
import com.jizza.platform.usecase.vita.sharing.VitaScopeResolver.PersonScope;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sharing a person's record inside a Family (spec 5.10, 7.16): presets or a custom matrix, the
 * person's own link ({@code as_self}), the consent note before any helper, the access list, notes
 * between the manager and helpers, and what is shared with the caller. Only the manager shares; a
 * member outside the manager's Family, or anything unclear, reads as not found. Who shared what
 * with whom goes to the audit log only, never to the application log.
 */
@Service
public class VitaSharingUseCase {

  public static final String RECALL_NOTE = "Pages already saved or printed cannot be recalled.";
  static final int CARE_NOTES_SHOWN = 100;
  static final int CARE_NOTE_MAX = 2000;

  /** The four presets of spec 7.16 (01-vita-health-spec.md, "Family and sharing"). */
  public enum Preset {
    /** The person's own login: Edit on medicines and vitals, View on the rest. */
    SELF,
    /** View on the emergency card only. */
    EMERGENCY,
    /** Edit on medicines and vitals; View on labs, visits and documents. */
    CAREGIVER,
    /** View on every section except the journal. */
    EVERYTHING_EXCEPT_JOURNAL;

    public String key() {
      return name().toLowerCase(Locale.ROOT);
    }

    static Preset of(String key) {
      if (key == null || key.isBlank()) return null;
      try {
        return valueOf(key.strip().toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException e) {
        throw new ValidationException("preset must be one of self, emergency, caregiver, everything_except_journal.");
      }
    }

    public Map<VitaSection, String> sections() {
      Map<VitaSection, String> m = new EnumMap<>(VitaSection.class);
      switch (this) {
        case SELF -> {
          for (VitaSection s : VitaSection.values()) m.put(s, "view");
          m.put(VitaSection.MEDICINES, "edit");
          m.put(VitaSection.VITALS, "edit");
        }
        case EMERGENCY -> m.put(VitaSection.EMERGENCY, "view");
        case CAREGIVER -> {
          m.put(VitaSection.MEDICINES, "edit");
          m.put(VitaSection.VITALS, "edit");
          m.put(VitaSection.LABS, "view");
          m.put(VitaSection.VISITS, "view");
          m.put(VitaSection.DOCUMENTS, "view");
        }
        case EVERYTHING_EXCEPT_JOURNAL -> {
          for (VitaSection s : VitaSection.values()) if (s != VitaSection.JOURNAL) m.put(s, "view");
        }
      }
      return m;
    }
  }

  private final VitaScopeResolver scopes;
  private final VitaShares shares;
  private final WorkspaceDirectory workspaces;
  private final VitaRecords records;
  private final NotificationBus bus;
  private final AuditLog audit;

  public VitaSharingUseCase(VitaScopeResolver scopes, VitaShares shares, WorkspaceDirectory workspaces,
                            VitaRecords records, NotificationBus bus, AuditLog audit) {
    this.scopes = scopes;
    this.shares = shares;
    this.workspaces = workspaces;
    this.records = records;
    this.bus = bus;
    this.audit = audit;
  }

  // ── The manager's side ─────────────────────────────────────────────────────

  /**
   * The manager's active shares, one entry per person and member, with the Family's members to
   * share with and the presets. With {@code personId}, only that person's.
   */
  public ObjectNode list(UUID caller, UUID personId) {
    if (personId != null) scopes.person(caller, personId);
    ObjectNode out = Json.obj();
    Map<String, ObjectNode> links = new LinkedHashMap<>();
    for (Share s : shares.activeGrantedBy(caller)) {
      if (personId != null && !personId.equals(s.personId())) continue;
      String key = s.personId() + "/" + s.granteeUserId();
      ObjectNode link = links.computeIfAbsent(key, k -> newLink(caller, s));
      ((ObjectNode) link.get("sections")).put(s.section(), s.access());
      ((ArrayNode) link.get("shares")).addObject().put("id", s.id().toString()).put("section", s.section())
          .put("access", s.access()).put("grantedAt", s.grantedAt().toString());
      if (s.asSelf()) link.put("asSelf", true);
    }
    ArrayNode arr = out.putArray("links");
    links.values().forEach(arr::add);
    ArrayNode members = out.putArray("members");
    familyMembers(caller).forEach((id, email) -> members.addObject().put("userId", id.toString()).put("email", email));
    out.put("hasFamily", hasFamily(caller));
    ArrayNode presets = out.putArray("presets");
    for (Preset p : Preset.values()) {
      ObjectNode n = presets.addObject().put("key", p.key());
      ObjectNode secs = n.putObject("sections");
      p.sections().forEach((s, a) -> secs.put(s.key(), a));
    }
    ArrayNode sections = out.putArray("sectionKeys");
    for (VitaSection s : VitaSection.values()) sections.add(s.key());
    out.put("note", RECALL_NOTE);
    return out;
  }

  private ObjectNode newLink(UUID caller, Share s) {
    ObjectNode link = Json.obj().put("personId", s.personId().toString())
        .put("personName", scopes.person(caller, s.personId()).person().displayName())
        .put("granteeUserId", s.granteeUserId().toString())
        .put("granteeEmail", workspaces.emailOf(s.granteeUserId()).orElse(null))
        .put("asSelf", false);
    link.putObject("sections");
    link.putArray("shares");
    return link;
  }

  /** What a POST /shares asks for. {@code sections} overrides the preset, section by section. */
  public record GrantRequest(UUID personId, UUID granteeUserId, String preset, Map<String, String> sections,
                             Boolean asSelf) {}

  /**
   * Sets which sections of one person's record one Family member may see, replacing what that
   * member had. "none" (or a section left out) ends that section's share. Linking the person as
   * themself needs no consent note; anyone else needs an open health consent note for the person,
   * unless the person is the manager's own "Me". Any Edit needs a fresh step-up sign-in.
   */
  @Transactional
  public ObjectNode grant(UUID caller, GrantRequest req, boolean stepUpFresh) {
    if (req == null || req.personId() == null) throw new ValidationException("personId is required.");
    if (req.granteeUserId() == null) throw new ValidationException("granteeUserId is required.");
    PersonScope p = scopes.person(caller, req.personId()).requireOpen();
    if (req.granteeUserId().equals(caller)) throw new ValidationException("You already manage this record.");
    UUID family = workspaces.sharedFamily(caller, req.granteeUserId())
        .orElseThrow(() -> new NotFoundException("Family member", req.granteeUserId()));

    Preset preset = Preset.of(req.preset());
    Map<VitaSection, String> matrix = new EnumMap<>(VitaSection.class);
    if (preset != null) matrix.putAll(preset.sections());
    if (req.sections() != null) {
      req.sections().forEach((k, v) -> {
        VitaSection s = VitaSection.of(k);
        if (s == null) throw new ValidationException("Unknown section " + k + ".");
        String a = v == null ? "none" : v.strip().toLowerCase(Locale.ROOT);
        if (a.equals("none")) matrix.remove(s);
        else if (a.equals("view") || a.equals("edit")) matrix.put(s, a);
        else throw new ValidationException("Access must be none, view or edit.");
      });
    }
    boolean asSelf = preset == Preset.SELF || Boolean.TRUE.equals(req.asSelf());
    if (matrix.isEmpty()) throw new ValidationException("Choose at least one section, or remove the share instead.");

    List<Share> current = shares.activeOf(caller, p.personId());
    if (asSelf) {
      if ("self".equals(p.person().kind())) throw new ValidationException("This is your own record.");
      if (current.stream().anyMatch(s -> s.asSelf() && !s.granteeUserId().equals(req.granteeUserId()))) {
        throw new ConflictException("Another member is already linked to this record as the person themself.");
      }
      if (shares.activeFor(req.granteeUserId()).stream()
          .anyMatch(s -> s.asSelf() && !s.personId().equals(p.personId()))) {
        throw new ConflictException("This member is already linked to another record as themself.");
      }
    } else if (!"self".equals(p.person().kind()) && !hasOpenConsent(caller, p.personId())) {
      throw new ValidationException("Record a consent note for " + p.person().displayName()
          + " first: when they agreed and how, under Sharing.");
    }
    if (matrix.containsValue("edit") && !stepUpFresh) throw new StepUpRequiredException();

    Map<VitaSection, Share> had = new EnumMap<>(VitaSection.class);
    for (Share s : current) {
      if (!s.granteeUserId().equals(req.granteeUserId())) continue;
      VitaSection sec = VitaSection.of(s.section());
      if (sec != null) had.put(sec, s);
    }
    for (Map.Entry<VitaSection, Share> e : had.entrySet()) {
      if (!matrix.containsKey(e.getKey())) shares.end(caller, e.getValue().id(), "revoked");
    }
    for (Map.Entry<VitaSection, String> e : matrix.entrySet()) {
      Share old = had.get(e.getKey());
      if (old != null && old.access().equals(e.getValue()) && old.asSelf() == asSelf) continue;
      shares.grant(caller, family, p.personId(), req.granteeUserId(), asSelf, e.getKey().key(), e.getValue());
    }
    ObjectNode detail = Json.obj().put("personId", p.personId().toString())
        .put("granteeUserId", req.granteeUserId().toString()).put("asSelf", asSelf);
    ObjectNode secs = detail.putObject("sections");
    matrix.forEach((s, a) -> secs.put(s.key(), a));
    audit.record(caller, ActorType.USER, caller, "vita.share.granted", p.personId().toString(), detail);
    return list(caller, p.personId());
  }

  /** Ends one section's share. */
  @Transactional
  public void revoke(UUID caller, UUID shareId) {
    Share s = shares.get(caller, shareId).filter(Share::active)
        .orElseThrow(() -> new NotFoundException("Share", shareId));
    shares.end(caller, shareId, "revoked");
    afterEnd(s.granteeUserId(), s.personId());
    audit.record(caller, ActorType.USER, caller, "vita.share.revoked", s.personId().toString(),
        Json.obj().put("granteeUserId", s.granteeUserId().toString()).put("section", s.section()));
  }

  /** Ends everything one member was given on one person's record. */
  @Transactional
  public int revokeAll(UUID caller, UUID personId, UUID granteeUserId) {
    scopes.person(caller, personId);
    int n = 0;
    for (Share s : shares.activeOf(caller, personId)) {
      if (!s.granteeUserId().equals(granteeUserId)) continue;
      if (shares.end(caller, s.id(), "revoked")) n++;
    }
    if (n == 0) throw new NotFoundException("Share", personId + "/" + granteeUserId);
    afterEnd(granteeUserId, personId);
    audit.record(caller, ActorType.USER, caller, "vita.share.revoked", personId.toString(),
        Json.obj().put("granteeUserId", granteeUserId.toString()).put("section", "all"));
    return n;
  }

  /**
   * After a share ends: when the member has no section of the record left, the reminders about
   * the person waiting in their inbox are filed away, so nothing of it is shown again.
   */
  void afterEnd(UUID granteeUserId, UUID personId) {
    if (!shares.activeFor(granteeUserId, personId).isEmpty()) return;
    try {
      for (NotificationDto n : bus.list(granteeUserId, false)) {
        JsonNode ctx = n.context();
        if (ctx == null || !personId.toString().equals(ctx.path("personId").asText(null))) continue;
        if (!ctx.path("type").asText("").startsWith("vita_") || n.archivedAt() != null) continue;
        bus.archive(n.id(), true);
      }
    } catch (UnsupportedOperationException ignored) {
      // A bus without an archive keeps them; the reminders themselves already stopped.
    }
  }

  /** Who can see what of one person's record and since when, ended shares too (manager only). */
  public ObjectNode accessList(UUID caller, UUID personId) {
    PersonScope p = scopes.person(caller, personId);
    ObjectNode out = Json.obj().put("personId", p.personId().toString()).put("personName", p.person().displayName());
    ArrayNode rows = out.putArray("entries");
    Map<UUID, String> emails = new LinkedHashMap<>();
    for (Share s : shares.allOf(caller, personId)) {
      String email = emails.computeIfAbsent(s.granteeUserId(), u -> workspaces.emailOf(u).orElse(null));
      rows.addObject().put("id", s.id().toString()).put("granteeUserId", s.granteeUserId().toString())
          .put("granteeEmail", email).put("asSelf", s.asSelf()).put("section", s.section()).put("access", s.access())
          .put("grantedAt", s.grantedAt().toString())
          .put("endedAt", s.endedAt() == null ? null : s.endedAt().toString())
          .put("endedReason", s.endedReason()).put("active", s.active());
    }
    out.put("note", RECALL_NOTE);
    return out;
  }

  // ── Consent notes ──────────────────────────────────────────────────────────

  public List<ObjectNode> consents(UUID caller, UUID personId) {
    PersonScope p = scopes.person(caller, personId);
    return shares.healthConsents(p.owner(), p.personId()).stream().map(VitaSharingUseCase::consentView).toList();
  }

  /** Records that the person agreed to share their health record ({@code scope} view or manage). */
  @Transactional
  public ObjectNode recordConsent(UUID caller, UUID personId, String scope, String channel) {
    PersonScope p = scopes.person(caller, personId).requireOpen();
    String s = scope == null || scope.isBlank() ? "view" : scope.strip().toLowerCase(Locale.ROOT);
    if (!s.equals("view") && !s.equals("manage")) throw new ValidationException("scope must be view or manage.");
    String c = channel == null || channel.isBlank() ? null : channel.strip();
    if (c != null && c.length() > 40) throw new ValidationException("How they agreed can be at most 40 characters.");
    VitaShares.Consent saved = shares.recordHealthConsent(p.owner(), p.personId(), s, c);
    audit.record(caller, ActorType.USER, caller, "vita.consent.recorded", p.personId().toString(), null);
    return consentView(saved);
  }

  /** Withdraws a consent note; when none stays open, every helper's share of the record ends. */
  @Transactional
  public ObjectNode withdrawConsent(UUID caller, UUID personId, UUID consentId) {
    PersonScope p = scopes.person(caller, personId);
    if (!shares.withdrawHealthConsent(p.owner(), consentId)) throw new NotFoundException("Consent note", consentId);
    if (!"self".equals(p.person().kind()) && !hasOpenConsent(p.owner(), p.personId())) {
      Set<UUID> helpers = new LinkedHashSet<>();
      for (Share s : shares.activeOf(p.owner(), p.personId())) {
        if (s.asSelf()) continue;
        shares.end(p.owner(), s.id(), "revoked");
        helpers.add(s.granteeUserId());
      }
      helpers.forEach(h -> afterEnd(h, p.personId()));
    }
    audit.record(caller, ActorType.USER, caller, "vita.consent.withdrawn", p.personId().toString(), null);
    return shares.healthConsents(p.owner(), p.personId()).stream().filter(c -> c.id().equals(consentId))
        .findFirst().map(VitaSharingUseCase::consentView).orElseThrow(() -> new NotFoundException("Consent note", consentId));
  }

  boolean hasOpenConsent(UUID owner, UUID personId) {
    return shares.healthConsents(owner, personId).stream().anyMatch(VitaShares.Consent::open);
  }

  private static ObjectNode consentView(VitaShares.Consent c) {
    return Json.obj().put("id", c.id().toString()).put("personId", c.personId().toString()).put("scope", c.scope())
        .put("channel", c.channel()).put("grantedAt", c.grantedAt().toString())
        .put("withdrawnAt", c.withdrawnAt() == null ? null : c.withdrawnAt().toString()).put("open", c.open());
  }

  // ── The member's side ──────────────────────────────────────────────────────

  /** Records shared with the caller, the person themself first, with the sections they may open. */
  public List<ObjectNode> sharedWithMe(UUID caller) {
    return scopes.sharedWithMe(caller).stream().map(p -> {
      ObjectNode n = Json.obj().put("personId", p.personId().toString())
          .put("name", p.grant().asSelf() ? "You" : p.person().displayName())
          .put("personName", p.person().displayName())
          .put("asSelf", p.grant().asSelf())
          .put("managerEmail", workspaces.emailOf(p.owner()).orElse(null));
      ObjectNode secs = n.putObject("sections");
      p.grant().sections().forEach((s, a) -> secs.put(s.key(), a));
      return n;
    }).toList();
  }

  /** Whether the caller is linked to their own record with Edit on medicines (for "My medicines today"). */
  public boolean linkedAsSelfWithMedicines(UUID caller) {
    return shares.activeFor(caller).stream()
        .anyMatch(s -> s.asSelf() && s.edit() && VitaSection.MEDICINES.key().equals(s.section())
            && scopes.sharedScope(caller, s.personId()).isPresent());
  }

  // ── Care notes (they follow the visits section) ─────────────────────────────

  public List<ObjectNode> careNotes(UUID caller, UUID personId) {
    PersonScope p = scopes.person(caller, personId, VitaSection.VISITS, false);
    return shares.careNotes(p.owner(), p.personId(), CARE_NOTES_SHOWN).stream().map(n -> noteView(p, n)).toList();
  }

  @Transactional
  public ObjectNode addCareNote(UUID caller, UUID personId, String text) {
    // Anyone who sees the visits may write a note, View included; a closed record takes none.
    PersonScope p = scopes.person(caller, personId, VitaSection.VISITS, false);
    if (!p.open()) throw new ValidationException("This record is closed and can only be read or exported.");
    String t = text == null ? "" : text.strip();
    if (t.isEmpty()) throw new ValidationException("Write the note first.");
    if (t.length() > CARE_NOTE_MAX) throw new ValidationException("A note can be at most " + CARE_NOTE_MAX + " characters.");
    VitaShares.CareNote n = shares.addCareNote(p.owner(), p.personId(), t, caller);
    audit.record(p.owner(), ActorType.USER, caller, "vita.care_note.added", n.id().toString(), null);
    return noteView(p, n);
  }

  private ObjectNode noteView(PersonScope p, VitaShares.CareNote n) {
    return Json.obj().put("id", n.id().toString()).put("text", n.text())
        .put("writtenBy", n.writtenByUserId() == null ? null : n.writtenByUserId().toString())
        .put("writtenByName", n.writtenByUserId() == null ? "Someone who left" : scopes.nameOf(p, n.writtenByUserId()))
        .put("writtenAt", n.writtenAt().toString());
  }

  // ── Doctors through a share ────────────────────────────────────────────────

  /**
   * The doctors a caller may read for one person: every doctor for the manager; for a member,
   * only the doctors named on the visits or medicines shared with them.
   */
  public List<ObjectNode> doctors(UUID caller, UUID personId) {
    PersonScope p;
    try {
      p = scopes.person(caller, personId, VitaSection.VISITS, false);
    } catch (NotFoundException noVisits) {
      p = scopes.person(caller, personId, VitaSection.MEDICINES, false);
    }
    List<ObjectNode> all = records.list(VitaTable.DOCTOR.table, p.owner(), Map.of()).stream()
        .filter(d -> d.path("deletedAt").isNull()).toList();
    if (p.manager()) return all;
    Set<String> named = new LinkedHashSet<>();
    final PersonScope scope = p;
    if (p.canView(VitaSection.VISITS)) {
      records.list(VitaTable.VISIT.table, p.owner(), VitaRecordsUseCase.livePerson(p.personId())).stream()
          .filter(r -> !VitaRecordsUseCase.hidden(scope, r)).forEach(r -> named.add(r.path("doctorId").asText("")));
    }
    if (p.canView(VitaSection.MEDICINES)) {
      records.list(VitaTable.MEDICINE.table, p.owner(), VitaRecordsUseCase.livePerson(p.personId())).stream()
          .filter(r -> !VitaRecordsUseCase.hidden(scope, r)).forEach(r -> named.add(r.path("prescribedBy").asText("")));
    }
    return all.stream().filter(d -> named.contains(d.path("id").asText())).map(d -> {
      ObjectNode v = Json.obj();
      for (String f : List.of("id", "kind", "name", "speciality", "clinic", "phone")) v.set(f, d.get(f));
      return v;
    }).toList();
  }

  // ── Family ──────────────────────────────────────────────────────────────────

  boolean hasFamily(UUID caller) {
    return workspaces.membershipsOf(caller).stream().anyMatch(m -> m.workspaceType() != null
        && m.workspaceType() == com.jizza.engine.api.enums.WorkspaceType.FAMILY
        && m.status() == com.jizza.engine.api.enums.MembershipStatus.ACTIVE);
  }

  /** The other active members of the caller's Families, by user id. */
  Map<UUID, String> familyMembers(UUID caller) {
    Map<UUID, String> out = new LinkedHashMap<>();
    for (var m : workspaces.membershipsOf(caller)) {
      if (m.workspaceType() != com.jizza.engine.api.enums.WorkspaceType.FAMILY
          || m.status() != com.jizza.engine.api.enums.MembershipStatus.ACTIVE) continue;
      for (var other : workspaces.members(m.workspaceId())) {
        if (other.userId().equals(caller) || other.status() != com.jizza.engine.api.enums.MembershipStatus.ACTIVE) continue;
        out.putIfAbsent(other.userId(), workspaces.emailOf(other.userId()).orElse(null));
      }
    }
    return out;
  }
}
