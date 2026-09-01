package com.jizza.platform.usecase.activity;

import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.dto.AuditEntryDto;
import com.jizza.engine.api.orchestrator.AgentDirectory;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The readable activity view over the audit log (R56), plus chain verification. Entries come back
 * from the engine without a resolved actor name -- labelling one as "You", "Jizza", or an agent's
 * name is a presentation call, not something the engine should decide.
 */
@Service
public class ActivityUseCase {

  private final AuditLog audit;
  private final AgentDirectory agents;

  public ActivityUseCase(AuditLog audit, AgentDirectory agents) {
    this.audit = audit;
    this.agents = agents;
  }

  public List<AuditEntryDto> recent(UUID userId, int limit, String action) {
    return audit.recent(userId, limit, action).stream().map(this::withActorName).toList();
  }

  /** Recomputes the chain so the user can confirm nothing has been altered or removed (R14). */
  public ChainVerificationResult verify(UUID userId) {
    long count = audit.countForUser(userId);
    return audit.verifyChain(userId)
        .map(broken -> new ChainVerificationResult(false, broken, count))
        .orElseGet(() -> new ChainVerificationResult(true, null, count));
  }

  private AuditEntryDto withActorName(AuditEntryDto e) {
    String actorName = switch (e.actorType()) {
      case AGENT -> e.actorId() == null ? "An agent" : agents.nameOf(e.actorId());
      case USER -> "You";
      case SYSTEM -> "Jizza";
    };
    return new AuditEntryDto(e.id(), e.actorType(), e.actorId(), actorName, e.action(), e.target(),
        e.detail(), e.requestId(), e.hash(), e.createdAt());
  }
}
