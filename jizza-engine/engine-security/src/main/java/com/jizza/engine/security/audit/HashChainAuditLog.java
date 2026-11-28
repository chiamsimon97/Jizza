package com.jizza.engine.security.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.dto.AuditEntryDto;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.util.Redaction;
import com.jizza.engine.persistence.entity.AuditLogEntity;
import com.jizza.engine.persistence.repository.AuditLogRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Append-only, hash-chained audit log. Writes take a per-user advisory lock so two concurrent
 * appends cannot both read the same chain head and fork it.
 *
 * <p>Each append runs in its own short transaction. That keeps the per-user lock held for
 * microseconds rather than for however long the caller's transaction lasts, which matters because
 * a caller may fan work out across threads while its own transaction is open -- with a shared lock
 * held for that whole span, those threads would wait on a transaction that is waiting on them.
 * It also means an attempt that was refused is still recorded when the caller goes on to throw.
 *
 * <p>The corollary is that an audit row can only reference a user that is already committed, so
 * anything creating a user must commit it before recording the event; see {@code AuthService}.
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
    // PostgreSQL keeps microseconds and rounds the rest, which can carry into the next
    // millisecond; truncating here hashes exactly the time that is read back.
    Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
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

  @Override
  @Transactional(readOnly = true)
  public List<AuditEntryDto> recent(UUID userId, int limit, String action) {
    var page = PageRequest.of(0, Math.max(1, Math.min(limit, 500)));
    List<AuditLogEntity> rows = action == null || action.isBlank()
        ? repository.findByUserIdOrderByCreatedAtDesc(userId, page)
        : repository.findByUserIdAndActionOrderBySeqDesc(userId, action, page);
    return rows.stream().map(this::toDto).toList();
  }

  @Override
  @Transactional(readOnly = true)
  public List<AuditEntryDto> between(UUID userId, Instant from, Instant to, int limit) {
    return repository.findWindow(userId, from == null ? Instant.EPOCH : from,
        to == null ? Instant.now().plusSeconds(60) : to, PageRequest.of(0, Math.max(1, limit)))
        .stream().map(this::toDto).toList();
  }

  @Override
  @Transactional(readOnly = true)
  public long countForUser(UUID userId) {
    return repository.countByUserId(userId);
  }

  private AuditEntryDto toDto(AuditLogEntity e) {
    return new AuditEntryDto(e.getId(), e.getActorType(), e.getActorId(), null, e.getAction(),
        e.getTarget(), e.getDetail(), e.getRequestId(), e.getHash(), e.getCreatedAt());
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
