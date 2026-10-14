package com.jizza.engine.api.cosmos;

import java.util.List;
import java.util.UUID;

/**
 * Everything placed in one body's part of the sky: the body itself and every moon under it. What
 * a reset of that body forgets, so it always matches what the sky shows there.
 *
 * @param subjectIds the subjects that are moons in this part of the sky
 */
public record BodyContents(String bodyId, List<UUID> factIds, List<Note> notes, List<UUID> subjectIds) {

  /** A note and the agent whose namespace it lives in. */
  public record Note(UUID id, UUID agentId) {}

  public BodyContents {
    factIds = List.copyOf(factIds);
    notes = List.copyOf(notes);
    subjectIds = List.copyOf(subjectIds);
  }
}
