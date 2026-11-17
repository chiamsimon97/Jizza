package com.jizza.engine.workflow.nodes;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WebStepFormDataTest {

  @Test
  void offersPageValuesButNotABrowserStepsOwnDetailsOrFlags() {
    Map<String, Object> variables = Map.of(
        "input", Map.of("title", "Trip to Pune", "from", "NDLS"),
        "nodes", Map.of(
            "open", Map.of("sessionId", "s-1", "tabId", 7, "url", "https://example.test/",
                "title", "Home", "navigated", true),
            "fare", Map.of("text", "₹ 1,250", "clicked", true, "set", false)));
    List<String> paths = new ArrayList<>();
    for (JsonNode each : WebStepForm.data(variables)) paths.add(each.path("path").asText());
    assertThat(paths).containsExactlyInAnyOrder("input.title", "input.from", "nodes.fare.text");
  }
}
