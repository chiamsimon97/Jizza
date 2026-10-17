package com.jizza.evals;

import static org.assertj.core.api.Assertions.assertThat;

import com.jizza.engine.api.enums.AutonomyRule;
import com.jizza.platform.usecase.agent.AgentTeamUseCase;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Every scenario file is well-formed and refers only to things that exist. */
class EvalCasesTest {

  private final List<EvalCase> cases = EvalCases.all();

  @Test
  void thereAreScenariosForJizzaAndEverySpecialist() {
    Set<String> covered = new HashSet<>();
    cases.forEach(c -> covered.add(c.agent()));
    AgentTeamUseCase.builtInTeam().forEach(spec -> assertThat(covered).as("scenarios for " + spec.key())
        .contains(spec.key()));
  }

  @Test
  void idsAreUnique() {
    assertThat(cases.stream().map(EvalCase::id).distinct().count()).isEqualTo(cases.size());
  }

  @Test
  void everyScenarioNamesABuiltInAgentAndOnlyItsOwnTools() {
    for (EvalCase c : cases) {
      EvalWorld.builtIn(c.agent());
      assertThat(c.instruction()).as(c.id()).isNotBlank();
      Set<String> tools = new HashSet<>();
      c.tools().forEach(t -> tools.add(t.name()));
      var expect = c.expect();
      Stream.of(expect.calls(), expect.callsAnyOf(), expect.notCalls(),
              List.copyOf(expect.decisions().keySet()), List.copyOf(expect.callArgsMention().keySet()))
          .flatMap(List::stream)
          .forEach(tool -> assertThat(tools).as(c.id() + " refers to " + tool).contains(tool));
    }
  }

  @Test
  void anExpectedDecisionMatchesWhatTheToolIsDeclaredToDo() {
    for (EvalCase c : cases) {
      c.expect().decisions().forEach((tool, rule) -> {
        var fixture = c.tools().stream().filter(t -> t.name().equals(tool)).findFirst().orElseThrow();
        boolean sideEffecting = fixture.actionType() == null || fixture.actionType().sideEffecting();
        assertThat(rule).as(c.id() + " " + tool)
            .isEqualTo(sideEffecting ? AutonomyRule.ASK : AutonomyRule.ALLOW);
      });
    }
  }

  @Test
  void everySafetyPromiseInTheBuiltInPromptsHasACriticalScenario() {
    assertThat(cases).filteredOn(EvalCase::critical).extracting(EvalCase::agent)
        .contains("finance", "health", "professional");
  }
}
