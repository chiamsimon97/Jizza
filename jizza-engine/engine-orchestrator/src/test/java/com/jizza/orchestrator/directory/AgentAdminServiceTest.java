package com.jizza.orchestrator.directory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jizza.common.audit.AuditLog;
import com.jizza.common.error.ValidationException;
import com.jizza.persistence.entity.AgentEntity;
import com.jizza.persistence.repository.AgentRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AgentAdminServiceTest {

  private AgentEntity vita;
  private AgentEntity jizza;
  private AuditLog audit;
  private AgentAdminService service;

  private AgentEntity agent(String key, String name, boolean orchestrator) {
    AgentEntity a = new AgentEntity();
    a.setId(UUID.randomUUID());
    a.setUserId(UUID.randomUUID());
    a.setKey(key);
    a.setName(name);
    a.setSystemPrompt("prompt");
    a.setOrchestrator(orchestrator);
    a.setEnabled(true);
    return a;
  }

  @BeforeEach
  void setUp() {
    vita = agent("health", "Vita", false);
    jizza = agent("jizza", "Jizza", true);
    AgentRepository agents = mock(AgentRepository.class);
    when(agents.findById(vita.getId())).thenReturn(Optional.of(vita));
    when(agents.findById(jizza.getId())).thenReturn(Optional.of(jizza));
    when(agents.save(any())).thenAnswer(inv -> inv.getArgument(0));
    audit = mock(AuditLog.class);
    service = new AgentAdminService(agents, audit);
  }

  @Test
  void renamesAnAgentAndRecordsTheChange() {
    var renamed = service.rename(vita.getId(), "Meera");
    assertThat(renamed.name()).isEqualTo("Meera");
    assertThat(renamed.key()).isEqualTo("health");
    verify(audit).record(eq(vita.getUserId()), any(), any(), eq("agent.renamed"), eq("health"), any());
  }

  @Test
  void theOrchestratorCanBeRenamedToo() {
    assertThat(service.rename(jizza.getId(), "Alfred").name()).isEqualTo("Alfred");
  }

  @Test
  void rejectsABlankOrOverlongName() {
    assertThatThrownBy(() -> service.rename(vita.getId(), "  "))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> service.rename(vita.getId(), "x".repeat(61)))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void disablesAndReenablesASpecialist() {
    assertThat(service.setEnabled(vita.getId(), false).enabled()).isFalse();
    assertThat(service.setEnabled(vita.getId(), true).enabled()).isTrue();
    verify(audit).record(any(), any(), any(), eq("agent.disabled"), eq("health"), any());
    verify(audit).record(any(), any(), any(), eq("agent.enabled"), eq("health"), any());
  }

  @Test
  void refusesToDisableTheOnlyWayTheUserReachesTheirAgents() {
    assertThatThrownBy(() -> service.setEnabled(jizza.getId(), false))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("kill switch");
    assertThat(jizza.isEnabled()).isTrue();
  }
}
