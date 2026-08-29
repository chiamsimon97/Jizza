package com.jizza.engine.connectors.tools.execute;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.connector.IdempotencyKey;
import com.jizza.engine.api.tool.ToolResult;
import com.jizza.engine.persistence.entity.IdempotencyKeyEntity;
import com.jizza.engine.persistence.repository.IdempotencyKeyRepository;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Exactly-once for side-effecting connector calls (R31). A separate bean so its short transactions
 * really do commit independently of the HTTP call they bracket -- claiming inside the caller's own
 * transaction would defeat the point, since the claim must be visible to other workers immediately.
 */
@Service
public class IdempotencyService {

  private final IdempotencyKeyRepository keys;

  public IdempotencyService(IdempotencyKeyRepository keys) {
    this.keys = keys;
  }

  /**
   * @return a result to return immediately (a replay, a conflict, or a call already in flight), or
   *         empty when the caller now owns the key and should proceed.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public Optional<ToolResult> claim(IdempotencyKey key, String tool, String fingerprint) {
    var existing = keys.findByUserIdAndKey(key.userId(), key.key());
    if (existing.isPresent()) {
      IdempotencyKeyEntity entity = existing.get();
      if (!fingerprint.equals(entity.getFingerprint())) {
        return Optional.of(ToolResult.error(
            "That idempotency key was already used for a different request"));
      }
      return switch (entity.getStatus()) {
        case COMPLETED -> Optional.of(ToolResult.ok(entity.getResult()));
        case IN_FLIGHT -> Optional.of(ToolResult.error(
            "An identical call is already in flight; it will not be sent twice"));
        // A previous attempt failed outright, so retrying is the right behaviour.
        case FAILED -> Optional.empty();
      };
    }

    IdempotencyKeyEntity entity = new IdempotencyKeyEntity();
    entity.setUserId(key.userId());
    entity.setKey(key.key());
    entity.setTool(tool);
    entity.setFingerprint(fingerprint);
    entity.setStatus(com.jizza.engine.api.enums.IdempotencyStatus.IN_FLIGHT);
    try {
      keys.save(entity);
    } catch (DataIntegrityViolationException e) {
      // The unique (user_id, key) index is what makes two workers safe: whoever inserted first owns
      // the call, and this one must not duplicate it.
      return Optional.of(ToolResult.error(
          "An identical call is already in flight; it will not be sent twice"));
    }
    return Optional.empty();
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void release(IdempotencyKey key, boolean ok, JsonNode output) {
    if (key == null) return;
    keys.findByUserIdAndKey(key.userId(), key.key()).ifPresent(entity -> {
      entity.setStatus(ok
          ? com.jizza.engine.api.enums.IdempotencyStatus.COMPLETED
          : com.jizza.engine.api.enums.IdempotencyStatus.FAILED);
      entity.setResult(output);
      keys.save(entity);
    });
  }
}
