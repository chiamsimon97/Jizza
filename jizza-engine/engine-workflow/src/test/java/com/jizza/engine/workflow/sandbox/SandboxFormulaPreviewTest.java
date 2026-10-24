package com.jizza.engine.workflow.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.jizza.engine.api.util.Json;
import org.junit.jupiter.api.Test;

class SandboxFormulaPreviewTest {

  private final SandboxFormulaPreview preview = new SandboxFormulaPreview(new JexlSandbox());
  private final com.fasterxml.jackson.databind.JsonNode data =
      Json.read("{\"input\":{\"name\":\" asha \"},\"nodes\":{\"fetch\":{\"body\":{\"price\":1500}}},\"secret\":1}");

  @Test
  void givesWhatAStepWouldWithItsType() {
    var formula = preview.preview("nodes.fetch.body.price * 2", false, data);
    assertThat(formula.value().asInt()).isEqualTo(3000);
    assertThat(formula.type()).isEqualTo("number");
    var template = preview.preview("Hi {{ input.name.trim().titleCase() }}", true, data);
    assertThat(template.value().asText()).isEqualTo("Hi Asha");
    assertThat(template.type()).isEqualTo("text");
  }

  @Test
  void explainsAFailureAndSeesOnlyRunData() {
    var bad = preview.preview("input.name.upper(", false, data);
    assertThat(bad.error()).contains("not valid");
    assertThat(bad.value()).isNull();
    assertThat(preview.preview("secret", false, data).error()).isNotNull();
    assertThat(preview.preview("vars", false, null).type()).isEqualTo("map");
  }
}
