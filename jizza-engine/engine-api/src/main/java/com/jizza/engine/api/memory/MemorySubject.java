package com.jizza.engine.api.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.enums.SubjectKind;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Someone or something in the user's life that memories are grouped around: a person ("Priya",
 * relation "wife"), an account, a plan, an organisation.
 *
 * @param relation     how the subject relates to the user, in the user's words ("wife")
 * @param connectionId the Connector this subject is reached through, for an account
 * @param domains      agent domains that may see this subject; empty means every agent may
 */
public record MemorySubject(UUID id, UUID userId, SubjectKind kind, String name, String relation,
                            List<String> aliases, JsonNode attributes, UUID connectionId,
                            List<String> domains, List<Relation> relations, Instant createdAt,
                            Instant updatedAt) {

  /** A typed edge to another subject ("Priya" --holds--> "Priya's Zerodha account"). */
  public record Relation(UUID id, UUID toSubjectId, String relation) {}
}
