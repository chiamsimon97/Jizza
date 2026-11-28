package com.jizza.platform.usecase.vita.records;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.finance.StatementFiles;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.vita.VitaRecords;
import com.jizza.engine.api.vita.VitaShares;
import com.jizza.platform.usecase.vita.sharing.VitaScopeResolver;
import com.jizza.platform.usecase.vita.sharing.VitaScopeResolver.PersonScope;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Export or delete one person's health record (spec 5.12, 5.13), for the manager only and behind
 * a fresh step-up sign-in at the route. Export works on a closed record too: a family needs it for
 * claims. Delete removes every Vita row of the person, the stored files of their documents, the
 * notes between helpers, and ends every share; the person stays in the household list, which Kai
 * shares.
 */
@Service
public class VitaPersonRecordUseCase {

  private final VitaScopeResolver scopes;
  private final VitaRecords records;
  private final VitaShares shares;
  private final StatementFiles files;
  private final AuditLog audit;

  public VitaPersonRecordUseCase(VitaScopeResolver scopes, VitaRecords records, VitaShares shares,
                                 StatementFiles files, AuditLog audit) {
    this.scopes = scopes;
    this.records = records;
    this.shares = shares;
    this.files = files;
    this.audit = audit;
  }

  /** Every Vita row of the person, the doctors their rows name, care notes and the access list. */
  public ObjectNode export(UUID caller, UUID personId) {
    PersonScope p = scopes.person(caller, personId);
    ObjectNode out = Json.obj().put("exportedAt", Instant.now().toString());
    ObjectNode person = out.putObject("person").put("id", p.personId().toString())
        .put("displayName", p.person().displayName()).put("kind", p.person().kind())
        .put("dob", p.person().dob() == null ? null : p.person().dob().toString())
        .put("status", p.person().status())
        .put("statusOn", p.person().statusOn() == null ? null : p.person().statusOn().toString());
    ObjectNode tables = out.putObject("records");
    Set<String> doctorIds = new LinkedHashSet<>();
    for (String table : records.tables()) {
      if (table.equals(VitaTable.DOCTOR.table)) continue;
      ArrayNode rows = tables.putArray(table);
      for (ObjectNode r : records.list(table, p.owner(), Map.of("personId", p.personId()))) {
        rows.add(r);
        for (String f : List.of("doctorId", "prescribedBy")) {
          if (r.hasNonNull(f)) doctorIds.add(r.get(f).asText());
        }
      }
    }
    ArrayNode doctors = tables.putArray(VitaTable.DOCTOR.table);
    records.list(VitaTable.DOCTOR.table, p.owner(), Map.of()).stream()
        .filter(d -> doctorIds.contains(d.path("id").asText())).forEach(doctors::add);
    ArrayNode notes = out.putArray("careNotes");
    shares.careNotes(p.owner(), p.personId(), Integer.MAX_VALUE).forEach(n -> notes.addObject()
        .put("text", n.text()).put("writtenAt", n.writtenAt().toString())
        .put("writtenBy", n.writtenByUserId() == null ? null : n.writtenByUserId().toString()));
    ArrayNode access = out.putArray("accessList");
    shares.allOf(p.owner(), p.personId()).forEach(s -> access.addObject()
        .put("granteeUserId", s.granteeUserId().toString()).put("section", s.section()).put("access", s.access())
        .put("asSelf", s.asSelf()).put("grantedAt", s.grantedAt().toString())
        .put("endedAt", s.endedAt() == null ? null : s.endedAt().toString()).put("endedReason", s.endedReason()));
    audit.record(p.owner(), ActorType.USER, caller, "vita.record.exported", p.personId().toString(), null);
    return out;
  }

  /** Deletes the person's health record for good. Returns how many rows went. */
  @Transactional
  public int delete(UUID caller, UUID personId) {
    PersonScope p = scopes.person(caller, personId);
    List<UUID> fileIds = new ArrayList<>();
    for (ObjectNode d : records.list(VitaTable.DOCUMENT.table, p.owner(), Map.of("personId", p.personId()))) {
      if (d.hasNonNull("fileId")) fileIds.add(UUID.fromString(d.get("fileId").asText()));
    }
    shares.endForPerson(p.owner(), p.personId(), "record_closed");
    int n = shares.deleteCareNotes(p.owner(), p.personId());
    List<String> tables = new ArrayList<>(records.tables());
    java.util.Collections.reverse(tables);
    for (String table : tables) {
      if (table.equals(VitaTable.DOCTOR.table)) continue;
      n += records.deleteWhere(table, p.owner(), Map.of("personId", p.personId()));
    }
    for (UUID fileId : fileIds) files.delete(p.owner(), fileId);
    audit.record(p.owner(), ActorType.USER, caller, "vita.record.deleted", p.personId().toString(), null);
    return n;
  }
}
