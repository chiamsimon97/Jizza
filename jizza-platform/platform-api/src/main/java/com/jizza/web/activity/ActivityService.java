package com.jizza.web.activity;

import com.jizza.common.audit.AuditLog;
import com.jizza.common.dto.AuditEntryDto;
import com.jizza.common.orchestrator.AgentDirectory;
import com.jizza.persistence.entity.AuditLogEntity;
import com.jizza.persistence.repository.AuditLogRepository;
import com.jizza.web.api.dto.Responses;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The readable activity view over the audit log (R56), plus chain verification.
 *
 * <p>Entries are rendered with the acting agent's name so the view reads as a record of what the
 * team did, rather than a table of identifiers.
 */
@Service
public class ActivityService {

  private final AuditLogRepository entries;
  private final AgentDirectory agents;
  private final AuditLog audit;

  public ActivityService(AuditLogRepository entries, AgentDirectory agents, AuditLog audit) {
    this.entries = entries;
    this.agents = agents;
    this.audit = audit;
  }

  @Transactional(readOnly = true)
  public List<AuditEntryDto> recent(UUID userId, int limit, String action) {
    var page = PageRequest.of(0, Math.max(1, Math.min(limit, 500)));
    List<AuditLogEntity> rows = action == null || action.isBlank()
        ? entries.findByUserIdOrderByCreatedAtDesc(userId, page)
        : entries.findByUserIdAndActionOrderBySeqDesc(userId, action, page);
    return rows.stream().map(this::toDto).toList();
  }

  /** Recomputes the chain so the user can confirm nothing has been altered or removed (R14). */
  @Transactional(readOnly = true)
  public Responses.ChainVerification verify(UUID userId) {
    long count = entries.findByUserIdOrderBySeqAsc(userId).size();
    return audit.verifyChain(userId)
        .map(broken -> new Responses.ChainVerification(false, broken, count))
        .orElseGet(() -> new Responses.ChainVerification(true, null, count));
  }

  private AuditEntryDto toDto(AuditLogEntity e) {
    String actorName = switch (e.getActorType()) {
      case AGENT -> e.getActorId() == null ? "An agent" : agents.nameOf(e.getActorId());
      case USER -> "You";
      case SYSTEM -> "Jizza";
    };
    return new AuditEntryDto(e.getId(), e.getActorType(), e.getActorId(), actorName, e.getAction(),
        e.getTarget(), e.getDetail(), e.getRequestId(), e.getHash(), e.getCreatedAt());
  }
}
