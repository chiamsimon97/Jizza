package com.jizza.security.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.common.audit.AuditLog;
import com.jizza.common.enums.ActorType;
import com.jizza.common.util.Json;
import com.jizza.common.util.Redaction;
import com.jizza.persistence.entity.AuditLogEntity;
import com.jizza.persistence.repository.AuditLogRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Append-only, hash-chained audit log. Writes take a per-user advisory lock so two concurrent
 * appends cannot both read the same chain head and fork it.
 *
 * <p>Runs in its own transaction: an action that fails afterwards still leaves the record that it
 * was attempted.
 */
@Service
public class HashChainAuditLog implements AuditLog {

  private final AuditLogRepository repository;

  @PersistenceContext
  private EntityManager em;

  public HashChainAuditLog(AuditLogRepository repository) {
    this.repository = repository;
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void record(UUID userId, ActorType actorType, UUID actorId, String action, String target,
                     JsonNode detail) {
    lockChain(userId);
    JsonNode safeDetail = redact(detail);
    Instant now = Instant.now();
    String prevHash = repository.findFirstByUserIdOrderBySeqDesc(userId)
        .map(AuditLogEntity::getHash).orElse(AuditHash.GENESIS);

    AuditLogEntity entry = new AuditLogEntity();
    entry.setUserId(userId);
    entry.setActorType(actorType);
    entry.setActorId(actorId);
    entry.setAction(action);
    entry.setTarget(target);
    entry.setDetail(safeDetail);
    entry.setPrevHash(prevHash);
    entry.setCreatedAt(now);
    entry.setRequestId(MDC.get("requestId"));
    entry.setHash(AuditHash.compute(prevHash, userId, actorType, actorId, action, target,
        safeDetail, now));
    repository.save(entry);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<UUID> verifyChain(UUID userId) {
    String prevHash = AuditHash.GENESIS;
    for (AuditLogEntity e : repository.findByUserIdOrderBySeqAsc(userId)) {
      String expected = AuditHash.compute(prevHash, e.getUserId(), e.getActorType(), e.getActorId(),
          e.getAction(), e.getTarget(), e.getDetail(), e.getCreatedAt());
      if (!expected.equals(e.getHash()) || !prevHash.equals(e.getPrevHash())) {
        return Optional.of(e.getId());
      }
      prevHash = e.getHash();
    }
    return Optional.empty();
  }

  /**
   * Detail is written by call sites all over the codebase, so it is scrubbed here: one careless
   * caller must not be able to put a token in the audit trail (R11).
   */
  private JsonNode redact(JsonNode detail) {
    if (detail == null || detail.isNull()) return null;
    return Json.read(Redaction.scrub(Json.write(detail)));
  }

  private void lockChain(UUID userId) {
    try {
      em.createNativeQuery("select pg_advisory_xact_lock(hashtext(:k))")
          .setParameter("k", "audit:" + userId)
          .getSingleResult();
    } catch (RuntimeException e) {
      // Backends without advisory locks still get a chain that verifies on read; only the
      // concurrent-append guarantee is PostgreSQL-specific.
    }
  }
}
