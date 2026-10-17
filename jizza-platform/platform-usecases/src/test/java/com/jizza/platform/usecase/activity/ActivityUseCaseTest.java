package com.jizza.platform.usecase.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.dto.AuditEntryDto;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.orchestrator.AgentDirectory;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Actor-name resolution is the one decision left once the engine hands back raw entries. */
class ActivityUseCaseTest {

  private final UUID userId = UUID.randomUUID();
  private AuditLog audit;
  private AgentDirectory agents;
  private ActivityUseCase useCase;

  private AuditEntryDto row(ActorType actorType, UUID actorId) {
    return new AuditEntryDto(UUID.randomUUID(), actorType, actorId, null, "workflow.saved",
        "target", null, "req-1", "hash", Instant.now());
  }

  @BeforeEach
  void setUp() {
    audit = mock(AuditLog.class);
    agents = mock(AgentDirectory.class);
    useCase = new ActivityUseCase(audit, agents);
  }

  @Test
  void userActionsAreLabelledYou() {
    when(audit.recent(userId, 100, null)).thenReturn(List.of(row(ActorType.USER, null)));

    assertThat(useCase.recent(userId, 100, null).get(0).actorName()).isEqualTo("You");
  }

  @Test
  void systemActionsAreLabelledJizza() {
    when(audit.recent(userId, 100, null)).thenReturn(List.of(row(ActorType.SYSTEM, null)));

    assertThat(useCase.recent(userId, 100, null).get(0).actorName()).isEqualTo("Jizza");
  }

  @Test
  void agentActionsAreLabelledWithTheAgentsName() {
    UUID agentId = UUID.randomUUID();
    when(audit.recent(userId, 100, null)).thenReturn(List.of(row(ActorType.AGENT, agentId)));
    when(agents.nameOf(agentId)).thenReturn("Scheduler");

    assertThat(useCase.recent(userId, 100, null).get(0).actorName()).isEqualTo("Scheduler");
  }

  @Test
  void anAgentActionWithNoActorIdIsLabelledGenerically() {
    when(audit.recent(userId, 100, null)).thenReturn(List.of(row(ActorType.AGENT, null)));

    assertThat(useCase.recent(userId, 100, null).get(0).actorName()).isEqualTo("An agent");
  }

  @Test
  void verifyReportsAnIntactChainWithItsEntryCount() {
    when(audit.countForUser(userId)).thenReturn(3L);
    when(audit.verifyChain(userId)).thenReturn(Optional.empty());

    var result = useCase.verify(userId);

    assertThat(result.intact()).isTrue();
    assertThat(result.firstBrokenEntryId()).isNull();
    assertThat(result.entries()).isEqualTo(3L);
  }

  @Test
  void verifyReportsTheFirstBrokenEntryWhenTheChainIsTampered() {
    UUID brokenId = UUID.randomUUID();
    when(audit.countForUser(userId)).thenReturn(5L);
    when(audit.verifyChain(userId)).thenReturn(Optional.of(brokenId));

    var result = useCase.verify(userId);

    assertThat(result.intact()).isFalse();
    assertThat(result.firstBrokenEntryId()).isEqualTo(brokenId);
  }
}
