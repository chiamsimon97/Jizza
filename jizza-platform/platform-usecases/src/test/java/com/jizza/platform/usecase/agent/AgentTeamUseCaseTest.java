package com.jizza.platform.usecase.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.jizza.engine.api.dto.AgentDto;
import com.jizza.engine.api.event.UserRegistered;
import com.jizza.engine.api.orchestrator.AgentDirectory;
import com.jizza.engine.api.orchestrator.AgentSpec;
import com.jizza.engine.api.orchestrator.AgentStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The team a new account gets, and what each member is told. */
class AgentTeamUseCaseTest {

  private final UUID userId = UUID.randomUUID();
  private final List<AgentDto> rows = new ArrayList<>();
  private AgentTeamUseCase useCase;

  /** Stands in for the engine's roster: idempotent by key, exactly as the real store is. */
  private final AgentStore store = new AgentStore() {
    @Override public AgentDto createIfAbsent(UUID owner, AgentSpec spec) {
      Optional<AgentDto> existing = rows.stream()
          .filter(a -> a.userId().equals(owner) && a.key().equals(spec.key())).findFirst();
      if (existing.isPresent()) return existing.get();
      AgentDto created = new AgentDto(UUID.randomUUID(), owner, spec.key(), spec.name(),
          spec.domain(), spec.systemPrompt(), spec.orchestrator(), true);
      rows.add(created);
      return created;
    }

    @Override public AgentDto rename(UUID agentId, String name) {
      throw new UnsupportedOperationException();
    }

    @Override public AgentDto setEnabled(UUID agentId, boolean enabled) {
      throw new UnsupportedOperationException();
    }
  };

  @BeforeEach
  void setUp() {
    AgentDirectory directory = new AgentDirectory() {
      @Override public List<AgentDto> list(UUID owner) {
        return rows.stream().filter(a -> a.userId().equals(owner)).toList();
      }

      @Override public Optional<AgentDto> byId(UUID agentId) {
        return rows.stream().filter(a -> a.id().equals(agentId)).findFirst();
      }

      @Override public Optional<AgentDto> byKey(UUID owner, String key) {
        return rows.stream().filter(a -> a.userId().equals(owner) && a.key().equals(key))
            .findFirst();
      }

      @Override public AgentDto orchestrator(UUID owner) {
        return list(owner).stream().filter(AgentDto::orchestrator).findFirst().orElseThrow();
      }

      @Override public String nameOf(UUID agentId) {
        return byId(agentId).map(AgentDto::name).orElse("An agent");
      }
    };
    useCase = new AgentTeamUseCase(store, directory);
  }

  @Test
  void provisionsJizzaAndTheThreeLaunchSpecialists() {
    useCase.onUserRegistered(new UserRegistered(userId, "owner@jizza.local"));

    assertThat(rows).extracting(AgentDto::key)
        .containsExactlyInAnyOrder("jizza", "health", "finance", "professional");
    assertThat(rows).extracting(AgentDto::name)
        .containsExactlyInAnyOrder("Jizza", "Vita", "Kai", "Cato");
  }

  @Test
  void exactlyOneAgentIsTheOrchestrator() {
    useCase.provision(userId);

    assertThat(rows.stream().filter(AgentDto::orchestrator).toList()).hasSize(1);
    assertThat(rows.stream().filter(AgentDto::orchestrator).findFirst().orElseThrow().key())
        .isEqualTo("jizza");
  }

  @Test
  void everyProvisionedAgentHasASystemPromptAndIsEnabled() {
    useCase.provision(userId);

    assertThat(rows).allSatisfy(agent -> {
      assertThat(agent.systemPrompt()).isNotBlank();
      assertThat(agent.enabled()).isTrue();
      assertThat(agent.userId()).isEqualTo(userId);
    });
  }

  @Test
  void financeIsToldToAlwaysAskBeforeMovingMoney() {
    useCase.provision(userId);

    String finance = rows.stream().filter(a -> a.key().equals("finance")).findFirst()
        .orElseThrow().systemPrompt();
    assertThat(finance).contains("moves money").contains("approval");
  }

  @Test
  void jizzaIsToldItIsTheOnlyOneTheUserTalksTo() {
    useCase.provision(userId);

    String jizza = rows.stream().filter(AgentDto::orchestrator).findFirst()
        .orElseThrow().systemPrompt();
    assertThat(jizza).contains("the only one they talk to");
    assertThat(jizza).as("and that nothing reaches the user except through it")
        .contains("Nothing reaches the user");
  }

  @Test
  void provisioningTwiceDoesNotDuplicateTheTeam() {
    useCase.provision(userId);
    useCase.provision(userId);

    assertThat(rows).hasSize(4);
  }
}
