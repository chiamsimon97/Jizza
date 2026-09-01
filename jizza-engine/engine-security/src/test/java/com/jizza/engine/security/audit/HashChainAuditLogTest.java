package com.jizza.engine.security.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.persistence.entity.AuditLogEntity;
import com.jizza.engine.persistence.repository.AuditLogRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HashChainAuditLogTest {

  private final UUID userId = UUID.randomUUID();

  private AuditLogEntity row(String action) {
    AuditLogEntity e = new AuditLogEntity();
    e.setId(UUID.randomUUID());
    e.setUserId(userId);
    e.setActorType(ActorType.USER);
    e.setAction(action);
    e.setTarget("target");
    e.setHash("hash");
    e.setCreatedAt(Instant.now());
    return e;
  }

  @Test
  void recentReturnsRowsWithoutResolvingAnActorName() {
    AuditLogRepository repository = mock(AuditLogRepository.class);
    when(repository.findByUserIdOrderByCreatedAtDesc(eq(userId), any()))
        .thenReturn(List.of(row("workflow.saved")));
    HashChainAuditLog audit = new HashChainAuditLog(repository);

    var entries = audit.recent(userId, 100, null);

    assertThat(entries).hasSize(1);
    assertThat(entries.get(0).action()).isEqualTo("workflow.saved");
    assertThat(entries.get(0).actorName()).isNull();
  }

  @Test
  void recentFiltersByActionWhenGiven() {
    AuditLogRepository repository = mock(AuditLogRepository.class);
    when(repository.findByUserIdAndActionOrderBySeqDesc(eq(userId), eq("workflow.saved"), any()))
        .thenReturn(List.of(row("workflow.saved")));
    HashChainAuditLog audit = new HashChainAuditLog(repository);

    var entries = audit.recent(userId, 100, "workflow.saved");

    assertThat(entries).hasSize(1);
  }

  @Test
  void countForUserDelegatesToTheRepository() {
    AuditLogRepository repository = mock(AuditLogRepository.class);
    when(repository.countByUserId(userId)).thenReturn(7L);
    HashChainAuditLog audit = new HashChainAuditLog(repository);

    assertThat(audit.countForUser(userId)).isEqualTo(7L);
  }
}
