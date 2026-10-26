package com.jizza.engine.workflow.functions;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.workflow.functions.ScriptFunctionDraft;
import com.jizza.engine.api.workflow.functions.ScriptFunctionDto;
import com.jizza.engine.api.workflow.graph.ModuleContract;
import com.jizza.engine.workflow.sandbox.FormulaRewriter;
import com.jizza.engine.workflow.sandbox.FunctionLookup;
import com.jizza.engine.workflow.sandbox.JexlSandbox;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Every public function compiles, has a usable name, and gives what its examples say. */
class PublicFunctionsTest {

  private final JexlSandbox sandbox = new JexlSandbox();

  @Test
  void thereAreAtLeastTenWithUniqueUsableNames() {
    List<ScriptFunctionDraft> drafts = PublicFunctions.drafts();
    assertThat(drafts).hasSizeGreaterThanOrEqualTo(10);
    Set<String> names = new HashSet<>();
    for (ScriptFunctionDraft d : drafts) {
      assertThat(ModuleContract.isName(d.name())).as(d.name()).isTrue();
      assertThat(FormulaRewriter.FUNCTIONS).as(d.name()).doesNotContain(d.name());
      assertThat(names.add(d.name())).as("%s is listed twice", d.name()).isTrue();
      assertThat(d.description()).as(d.name()).isNotBlank();
    }
  }

  @Test
  void eachGivesWhatItsExamplesSay() {
    for (JsonNode entry : PublicFunctions.entries()) {
      String name = entry.path("name").asText();
      ScriptFunctionDto f = new ScriptFunctionDto(null, null, name, null, entry.path("inputs"),
          entry.path("script").asText(), entry.path("returnType").asText(), entry.path("returnFields"),
          List.of(), null, null, "public");
      sandbox.check(f.script());
      assertThat(entry.path("examples").size()).as("%s has examples", name).isPositive();
      for (JsonNode example : entry.path("examples")) {
        Map<String, JsonNode> args = new LinkedHashMap<>();
        example.path("args").fields().forEachRemaining(e -> args.put(e.getKey(), e.getValue()));
        JsonNode got = sandbox.runFunction(f, args, FunctionLookup.NONE);
        assertThat(same(got, example.path("returns")))
            .as("fn.%s(%s) gave %s, expected %s", name, example.path("args"), got, example.path("returns")).isTrue();
        if ("map".equals(f.returnType())) {
          for (JsonNode field : f.returnFields()) {
            assertThat(got.has(field.path("path").asText())).as("%s gives %s", name, field.path("path")).isTrue();
          }
        }
      }
    }
  }

  /** Equal, with numbers compared by value (1000 and 1000.0 are the same). */
  private static boolean same(JsonNode a, JsonNode b) {
    if (a == null || a.isNull()) return b == null || b.isNull();
    if (a.isNumber() && b.isNumber()) return Math.abs(a.asDouble() - b.asDouble()) < 1e-9;
    if (a.isObject() && b.isObject()) {
      if (a.size() != b.size()) return false;
      for (var it = b.fields(); it.hasNext(); ) {
        var e = it.next();
        if (!same(a.get(e.getKey()), e.getValue())) return false;
      }
      return true;
    }
    return a.equals(b);
  }
}
