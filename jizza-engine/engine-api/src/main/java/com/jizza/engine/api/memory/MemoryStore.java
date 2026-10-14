package com.jizza.engine.api.memory;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The user's long-term memory: facts, the subjects they are about, and how those subjects relate.
 *
 * <p>Every method is scoped by {@code userId} in its query, so one user's memory is unreachable
 * from another's. Reads that serve an agent also take a {@link MemoryScope}, applied in the same
 * query, so a specialist only ever sees shared facts and facts in its own domain.
 *
 * <p>Writes deduplicate and resolve conflicts: remembering something already known reinforces the
 * existing fact, and a fact with the same key and subjects as an existing one replaces it when it
 * is at least as authoritative (confirmed beats unconfirmed, newer beats older), or waits as
 * pending for the user otherwise.
 */
public interface MemoryStore {

  MemoryWrite remember(UUID userId, MemoryDraft draft, Actor actor);

  /** The user correcting a fact. Keeps the previous wording in its history; marks it confirmed. */
  Memory correct(UUID userId, UUID memoryId, MemoryDraft changes, Actor actor);

  /** Confirms a pending or unconfirmed fact; a pending one replaces whatever it conflicted with. */
  Memory confirm(UUID userId, UUID memoryId, Actor actor);

  /** An agent marking a fact as no longer true. Reversible: the user can still see and restore it. */
  Memory retire(UUID userId, UUID memoryId, String reason, Actor actor);

  /** Back to active after a retire or supersede. */
  Memory restore(UUID userId, UUID memoryId, Actor actor);

  /** Permanent: the fact and its history are deleted. */
  void forget(UUID userId, UUID memoryId, Actor actor);

  Optional<Memory> get(UUID userId, UUID memoryId);

  List<Memory> list(UUID userId, MemoryFilter filter);

  List<MemoryChange> history(UUID userId, UUID memoryId);

  /** Active facts ranked by meaning and keywords against {@code query}, within {@code scope}. */
  List<Memory> search(UUID userId, String query, MemoryScope scope, int limit);

  /** Creates the subject, or updates the one with the same name (or alias) and kind. */
  MemorySubject upsertSubject(UUID userId, SubjectDraft draft, Actor actor);

  MemorySubject updateSubject(UUID userId, UUID subjectId, SubjectDraft draft, Actor actor);

  List<MemorySubject> subjects(UUID userId, MemoryScope scope);

  Optional<MemorySubject> subject(UUID userId, UUID subjectId);

  /** Facts about the subject stay, unlinked from it. */
  void deleteSubject(UUID userId, UUID subjectId, Actor actor);

  MemorySubject.Relation relate(UUID userId, UUID fromSubjectId, UUID toSubjectId, String relation,
                                Actor actor);

  void unrelate(UUID userId, UUID relationId, Actor actor);

  MemorySettings settings(UUID userId);

  MemorySettings updateSettings(UUID userId, MemorySettings settings);
}
