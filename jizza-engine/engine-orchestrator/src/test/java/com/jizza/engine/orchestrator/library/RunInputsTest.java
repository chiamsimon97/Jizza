package com.jizza.engine.orchestrator.library;

import static org.assertj.core.api.Assertions.assertThat;

import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.workflow.library.ReuseProfile;
import java.util.List;
import org.junit.jupiter.api.Test;

class RunInputsTest {

  @Test
  void readsTheStartStepsCatalogue() {
    var inputs = RunInputs.of(Json.read(ChatRunPlannerTest.GRAPH), null);
    assertThat(inputs).extracting(RunInputs.Input::name)
        .containsExactly("from", "to", "date", "travelClass", "passengers", "train");
    assertThat(inputs.get(5).atStep()).isTrue();
    assertThat(inputs.get(3).options()).containsExactly("Sleeper (SL)", "AC 3 Tier (3A)");
    assertThat(RunInputs.usesBrowser(Json.read(ChatRunPlannerTest.GRAPH))).isTrue();
  }

  @Test
  void fallsBackToTheProfileForAWorkflowThatDeclaresNoInputs() {
    var profile = new ReuseProfile(null, List.of(), List.of(new ReuseProfile.Input("movie", "Which film", true)),
        ReuseProfile.Guardrails.DEFAULT, null);
    var inputs = RunInputs.of(Json.read("{\"nodes\":[]}"), profile);
    assertThat(inputs).singleElement().satisfies(i -> {
      assertThat(i.name()).isEqualTo("movie");
      assertThat(i.required()).isTrue();
    });
    assertThat(RunInputs.usesBrowser(Json.read("{\"nodes\":[]}"))).isFalse();
  }

  @Test
  void checksValuesWithTheRunsOwnRules() {
    var inputs = RunInputs.of(Json.read(ChatRunPlannerTest.GRAPH), null);
    var checked = RunInputs.check(inputs, Json.read("""
        {"From": "MAS", "travel_class": "Third AC", "passengers": "not a list", "unknown": "x", "to": ""}"""));
    assertThat(checked.values().path("from").asText()).isEqualTo("MAS");
    assertThat(checked.values().path("travelClass").asText()).isEqualTo("AC 3 Tier (3A)");
    assertThat(checked.values().has("unknown")).isFalse();
    assertThat(checked.values().has("to")).isFalse();
    assertThat(checked.problems()).containsOnlyKeys("passengers");
  }
}
