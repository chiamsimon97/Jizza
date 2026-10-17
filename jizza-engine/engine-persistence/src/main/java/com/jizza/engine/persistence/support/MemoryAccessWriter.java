package com.jizza.engine.persistence.support;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.Collection;
import java.util.List;
import java.util.StringJoiner;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes read stamps. Each item gets at most one stamp per agent and run (the unique index on
 * {@code memory_access}); only a new stamp counts as a use, so {@code access_count} counts runs,
 * not repeated reads within one. Items are joined to their owner in the insert, so a stamp can
 * never be written for another user's item.
 */
@Component
public class MemoryAccessWriter {

  @PersistenceContext
  private EntityManager em;

  /** @return how many items were stamped for the first time in this run */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public int stampFacts(UUID userId, UUID agentId, Collection<UUID> ids, String contextKind,
                        String contextRef) {
    return stamp("memories", "fact", userId, agentId, ids, contextKind, contextRef);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public int stampNotes(UUID userId, UUID agentId, Collection<UUID> ids, String contextKind,
                        String contextRef) {
    return stamp("knowledge_entries", "note", userId, agentId, ids, contextKind, contextRef);
  }

  @SuppressWarnings("unchecked")
  private int stamp(String table, String kind, UUID userId, UUID agentId, Collection<UUID> ids,
                    String contextKind, String contextRef) {
    if (ids.isEmpty()) return 0;
    StringJoiner array = new StringJoiner(",", "{", "}");
    for (UUID id : ids) array.add(id.toString());
    List<Object> stamped = em.createNativeQuery("""
            insert into memory_access (user_id, item_kind, item_id, agent_id, context_kind, context_ref)
            select :userId, :kind, i.id, :agentId, :contextKind, :contextRef
              from %s i
             where i.id = any (cast(:ids as uuid[])) and i.user_id = :userId
            on conflict do nothing
            returning item_id
            """.formatted(table))
        .setParameter("userId", userId)
        .setParameter("kind", kind)
        .setParameter("agentId", agentId)
        .setParameter("contextKind", contextKind)
        .setParameter("contextRef", contextRef)
        .setParameter("ids", array.toString())
        .getResultList();
    if (stamped.isEmpty()) return 0;
    em.createNativeQuery("""
            update %s set access_count = access_count + 1, last_accessed_at = now()
             where id in (:ids) and user_id = :userId
            """.formatted(table))
        .setParameter("ids", stamped.stream().map(o -> (UUID) o).toList())
        .setParameter("userId", userId)
        .executeUpdate();
    return stamped.size();
  }

  /** Stamps whose item is gone: retention deletes facts in bulk, without going through the store. */
  @Transactional
  public int deleteOrphans() {
    return em.createNativeQuery("""
            delete from memory_access a
             where (a.item_kind = 'fact' and not exists (select 1 from memories m where m.id = a.item_id))
                or (a.item_kind = 'note' and not exists (select 1 from knowledge_entries k where k.id = a.item_id))
            """).executeUpdate();
  }
}
