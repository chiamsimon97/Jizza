package com.jizza.engine.workflow.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.error.SandboxViolationException;
import com.jizza.engine.api.util.Json;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Keeps the formula editor and the engine in step. The editor lives in jizza-client; these read its
 * shared cases and its function catalog from the checkout, and are skipped when it isn't there.
 */
class FormulaConformanceTest {

  private static final Path FORMULA_DIR = Path.of("../../jizza-client/src/components/workflow/formula");

  private static String read(String file) throws Exception {
    Path path = FORMULA_DIR.resolve(file);
    Assumptions.assumeTrue(Files.exists(path), "the client is not checked out next to the engine");
    return Files.readString(path);
  }

  @Test
  void everySharedCaseGivesWhatTheEditorExpects() throws Exception {
    JsonNode corpus = Json.read(read("cases.json"));
    @SuppressWarnings("unchecked")
    Map<String, Object> data = Json.mapper().convertValue(corpus.get("data"), Map.class);
    JexlSandbox sandbox = new JexlSandbox();
    for (JsonNode c : corpus.get("cases")) {
      String formula = c.get("formula").asText();
      if (c.has("result")) {
        JsonNode got = sandbox.evaluate(formula, data);
        JsonNode want = c.get("result");
        if (want.isNumber()) assertThat(got.asDouble()).as(formula).isEqualTo(want.asDouble());
        else assertThat(got).as(formula).isEqualTo(want);
      } else {
        assertThatThrownBy(() -> sandbox.evaluate(formula, data)).as(formula)
            .isInstanceOf(SandboxViolationException.class);
      }
    }
  }

  @Test
  void theEditorOffersExactlyTheFunctionsTheEngineRuns() throws Exception {
    String catalog = read("functions.ts");
    String offered = catalog.substring(catalog.indexOf("FORMULA_FUNCTIONS"), catalog.indexOf("BUILT_INS"));
    Set<String> names = new TreeSet<>();
    var m = Pattern.compile("\\{ name: '([A-Za-z]+)', category:").matcher(offered);
    while (m.find()) names.add(m.group(1));
    assertThat(names).isEqualTo(new TreeSet<>(FormulaRewriter.FUNCTIONS));
  }
}
