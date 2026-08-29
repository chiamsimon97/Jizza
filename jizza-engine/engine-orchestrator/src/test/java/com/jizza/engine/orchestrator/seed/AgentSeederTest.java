package com.jizza.engine.orchestrator.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jizza.engine.api.event.UserRegistered;
import com.jizza.engine.persistence.entity.AgentEntity;
import com.jizza.engine.persistence.repository.AgentRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AgentSeederTest {

  private final UUID userId = UUID.randomUUID();
  private final List<AgentEntity> rows = new ArrayList<>();
  private AgentSeeder seeder;

  @BeforeEach
  void setUp() {
    AgentRepository agents = mock(AgentRepository.class);
    when(agents.save(any())).thenAnswer(inv -> {
      AgentEntity a = inv.getArgument(0);
      a.setId(UUID.randomUUID());
      rows.add(a);
      return a;
    });
    when(agents.findByUserIdAndKey(any(), any())).thenAnswer(inv -> rows.stream()
        .filter(a -> a.getUserId().equals(inv.getArgument(0)) && a.getKey().equals(inv.getArgument(1)))
        .findFirst());
    when(agents.findByUserIdOrderByIsOrchestratorDescNameAsc(any()))
        .thenAnswer(inv -> rows.stream()
            .filter(a -> a.getUserId().equals(inv.<UUID>getArgument(0))).toList());
    seeder = new AgentSeeder(agents);
  }

  @Test
  void seedsJizzaAndTheThreeLaunchSpecialists() {
    seeder.onUserRegistered(new UserRegistered(userId, "owner@jizza.local"));

    assertThat(rows).extracting(AgentEntity::getKey)
        .containsExactlyInAnyOrder("jizza", "health", "finance", "professional");
    assertThat(rows).extracting(AgentEntity::getName)
        .containsExactlyInAnyOrder("Jizza", "Vita", "Kai", "Cato");
  }

  @Test
  void exactlyOneAgentIsTheOrchestrator() {
    seeder.seedFor(userId);
    assertThat(rows.stream().filter(AgentEntity::isOrchestrator).toList()).hasSize(1);
    assertThat(rows.stream().filter(AgentEntity::isOrchestrator).findFirst().orElseThrow().getKey())
        .isEqualTo("jizza");
  }

  @Test
  void everySeededAgentHasASystemPromptAndIsEnabled() {
    seeder.seedFor(userId);
    assertThat(rows).allSatisfy(agent -> {
      assertThat(agent.getSystemPrompt()).isNotBlank();
      assertThat(agent.isEnabled()).isTrue();
      assertThat(agent.getUserId()).isEqualTo(userId);
    });
  }

  @Test
  void financeIsToldToAlwaysAskBeforeMovingMoney() {
    seeder.seedFor(userId);
    String finance = rows.stream().filter(a -> a.getKey().equals("finance")).findFirst()
        .orElseThrow().getSystemPrompt();
    assertThat(finance).contains("moves money").contains("approval");
  }

  @Test
  void seedingTwiceDoesNotDuplicateTheTeam() {
    seeder.seedFor(userId);
    seeder.seedFor(userId);
    assertThat(rows).hasSize(4);
  }
}
