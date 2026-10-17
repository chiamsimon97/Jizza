package com.jizza.platform.usecase.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.dto.AgentDto;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.orchestrator.AgentDirectory;
import com.jizza.engine.api.orchestrator.AgentSpec;
import com.jizza.engine.api.orchestrator.AgentStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** What the user may change about their agents, and the one thing they may not. */
class AgentAdminUseCaseTest {

  private final UUID userId = UUID.randomUUID();
  private final List<AgentDto> rows = new ArrayList<>();
  private AgentDto vita;
  private AgentDto jizza;
  private AuditLog audit;
  private AgentAdminUseCase useCase;

  private AgentDto agent(String key, String name, boolean orchestrator) {
    AgentDto a = new AgentDto(UUID.randomUUID(), userId, key, name, null, "prompt", orchestrator,
        true);
    rows.add(a);
    return a;
  }

  private void replace(UUID id, AgentDto updated) {
    rows.replaceAll(a -> a.id().equals(id) ? updated : a);
  }

  private AgentDto current(UUID id) {
    return rows.stream().filter(a -> a.id().equals(id)).findFirst().orElseThrow();
  }

  @BeforeEach
  void setUp() {
    rows.clear();
    vita = agent("health", "Vita", false);
    jizza = agent("jizza", "Jizza", true);

    AgentStore store = new AgentStore() {
      @Override public AgentDto createIfAbsent(UUID owner, AgentSpec spec) {
        throw new UnsupportedOperationException();
      }

      @Override public AgentDto rename(UUID agentId, String name) {
        AgentDto a = current(agentId);
        AgentDto updated = new AgentDto(a.id(), a.userId(), a.key(), name, a.domain(),
            a.systemPrompt(), a.orchestrator(), a.enabled());
        replace(agentId, updated);
        return updated;
      }

      @Override public AgentDto setEnabled(UUID agentId, boolean enabled) {
        AgentDto a = current(agentId);
        AgentDto updated = new AgentDto(a.id(), a.userId(), a.key(), a.name(), a.domain(),
            a.systemPrompt(), a.orchestrator(), enabled);
        replace(agentId, updated);
        return updated;
      }
    };

    AgentDirectory directory = new AgentDirectory() {
      @Override public List<AgentDto> list(UUID owner) {
        return rows.stream().filter(a -> a.userId().equals(owner)).toList();
      }

      @Override public Optional<AgentDto> byId(UUID agentId) {
        return rows.stream().filter(a -> a.id().equals(agentId)).findFirst();
      }

      @Override public Optional<AgentDto> byKey(UUID owner, String key) {
        return list(owner).stream().filter(a -> a.key().equals(key)).findFirst();
      }

      @Override public AgentDto orchestrator(UUID owner) {
        return list(owner).stream().filter(AgentDto::orchestrator).findFirst().orElseThrow();
      }

      @Override public String nameOf(UUID agentId) {
        return byId(agentId).map(AgentDto::name).orElse("An agent");
      }
    };

    audit = mock(AuditLog.class);
    useCase = new AgentAdminUseCase(store, directory, audit);
  }

  @Test
  void renamesAnAgentAndRecordsTheChange() {
    var renamed = useCase.rename(userId, vita.id(), "Meera");

    assertThat(renamed.name()).isEqualTo("Meera");
    assertThat(renamed.key()).isEqualTo("health");
    verify(audit).record(eq(userId), any(), any(), eq("agent.renamed"), eq("health"), any());
  }

  @Test
  void aNameIsTidiedOnTheWayIn() {
    assertThat(useCase.rename(userId, vita.id(), "  Meera  ").name()).isEqualTo("Meera");
  }

  @Test
  void theOrchestratorCanBeRenamedToo() {
    assertThat(useCase.rename(userId, jizza.id(), "Alfred").name()).isEqualTo("Alfred");
  }

  @Test
  void rejectsABlankOrOverlongName() {
    assertThatThrownBy(() -> useCase.rename(userId, vita.id(), "  "))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> useCase.rename(userId, vita.id(), "x".repeat(61)))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void disablesAndReenablesASpecialist() {
    assertThat(useCase.setEnabled(userId, vita.id(), false).enabled()).isFalse();
    assertThat(useCase.setEnabled(userId, vita.id(), true).enabled()).isTrue();

    verify(audit).record(any(), any(), any(), eq("agent.disabled"), eq("health"), any());
    verify(audit).record(any(), any(), any(), eq("agent.enabled"), eq("health"), any());
  }

  @Test
  void refusesToDisableTheOnlyWayTheUserReachesTheirAgents() {
    assertThatThrownBy(() -> useCase.setEnabled(userId, jizza.id(), false))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("kill switch");
    assertThat(current(jizza.id()).enabled()).isTrue();
  }

  @Test
  void anotherUsersAgentIsReportedMissingRatherThanForbidden() {
    assertThatThrownBy(() -> useCase.rename(UUID.randomUUID(), vita.id(), "Meera"))
        .isInstanceOf(NotFoundException.class);
  }
}
