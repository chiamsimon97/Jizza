package com.jizza.engine.api.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.enums.SubjectKind;
import java.util.List;
import java.util.UUID;

/** A subject to create or update. Null fields keep their current (or default) values. */
public record SubjectDraft(SubjectKind kind, String name, String relation, List<String> aliases,
                           JsonNode attributes, UUID connectionId, List<String> domains) {

  public static SubjectDraft of(SubjectKind kind, String name, String relation) {
    return new SubjectDraft(kind, name, relation, null, null, null, null);
  }
}
