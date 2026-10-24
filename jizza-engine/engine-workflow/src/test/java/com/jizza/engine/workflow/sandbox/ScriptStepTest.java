package com.jizza.engine.workflow.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jizza.engine.api.error.SandboxViolationException;
import com.jizza.engine.api.util.Json;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ScriptStepTest {

  private final JexlSandbox sandbox = new JexlSandbox();
  private final Map<String, Object> run = Map.of("nodes", Map.of("fetch", Map.of("body", Map.of(
      "lines", List.of(Map.of("sku", "A", "qty", 2, "price", 100), Map.of("sku", "B", "qty", 1, "price", 50)),
      "customer", " asha rao "))));

  private static String step(String script) {
    return "{\"inputs\":[{\"name\":\"lines\",\"type\":\"list\",\"value\":\"{{ nodes.fetch.body.lines }}\"},"
        + "{\"name\":\"customer\",\"type\":\"text\",\"value\":\"{{ nodes.fetch.body.customer }}\"},"
        + "{\"name\":\"discount\",\"type\":\"number\",\"value\":\"10\"}],"
        + "\"script\":" + Json.toNode(script) + ","
        + "\"outputs\":[{\"name\":\"total\",\"type\":\"number\"},{\"name\":\"greeting\",\"type\":\"text\"}]}";
  }

  @Test
  void runsLinesOfLogicOverItsInputsAndGivesItsOutputs() {
    String script = """
        // add up the order, then take the discount off big ones
        var total = 0;
        for (var line : lines) {
          total = total + line.qty * line.price;
        }
        if (total > 200) {
          total = total - discount;
        }
        var greeting = 'Hi ' + customer.trim().titleCase();
        return {'total': total, 'greeting': greeting, 'ignored': true};
        """;
    var out = ScriptStep.run(sandbox, null, null, Json.read(step(script)), run);
    assertThat(out.path("total").asDouble()).isEqualTo(240);
    assertThat(out.path("greeting").asText()).isEqualTo("Hi Asha Rao");
    assertThat(out.has("ignored")).as("only declared outputs come out").isFalse();
  }

  @Test
  void saysWhatIsWrongWithAScript() {
    assertThatThrownBy(() -> ScriptStep.run(sandbox, null, null, Json.read(step("var x = 1; while (true) { x = x + 1; }")), run))
        .isInstanceOf(SandboxViolationException.class).hasMessageContaining("while and do loops are not allowed");
    assertThatThrownBy(() -> ScriptStep.run(sandbox, null, null, Json.read(step("lines.count()")), run))
        .hasMessageContaining("should end with return");
    assertThatThrownBy(() -> ScriptStep.run(sandbox, null, null, Json.read(step("return {'total': 'lots'};")), run))
        .hasMessageContaining("'total' should be a number");
    assertThatThrownBy(() -> ScriptStep.run(sandbox, null, null, Json.read(step("nodes.fetch")), run))
        .as("a script sees its inputs, not the whole run").isInstanceOf(SandboxViolationException.class);
  }

  @Test
  void aScriptCannotReachOutsideItsInputs() {
    assertThatThrownBy(() -> sandbox.runScript("new('java.io.File', '/etc/passwd').exists()", Map.of()))
        .isInstanceOf(SandboxViolationException.class);
    assertThatThrownBy(() -> sandbox.runScript("function f() { 1 }; f()", Map.of()))
        .isInstanceOf(SandboxViolationException.class);
    assertThat(sandbox.runScript("// do it twice, don't worry\nvar n = 2; n * 2", Map.of()).asInt()).isEqualTo(4);
  }
}
