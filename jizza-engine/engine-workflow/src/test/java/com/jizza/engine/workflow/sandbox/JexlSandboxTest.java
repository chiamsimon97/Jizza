package com.jizza.engine.workflow.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jizza.engine.api.error.SandboxViolationException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JexlSandboxTest {

  private final JexlSandbox sandbox = new JexlSandbox();

  private void refuses(String expression) {
    assertThatThrownBy(() -> sandbox.evaluate(expression, Map.of("input", Map.of("a", 1))))
        .as(expression)
        .isInstanceOf(SandboxViolationException.class);
  }

  @Test
  void shapesDataWhichIsTheWholePointOfTheNode() {
    var result = sandbox.evaluate("input.amount * 2",
        Map.of("input", Map.of("amount", 21)));
    assertThat(result.asInt()).isEqualTo(42);
  }

  @Test
  void readsNestedValuesAndBuildsNewOnes() {
    var result = sandbox.evaluate("{'name': input.user.name, 'upper': input.user.name.toUpperCase()}",
        Map.of("input", Map.of("user", Map.of("name", "vita"))));
    assertThat(result.path("name").asText()).isEqualTo("vita");
    assertThat(result.path("upper").asText()).isEqualTo("VITA");
  }

  @Test
  void comparesAndBranches() {
    assertThat(sandbox.evaluate("input.total > 1000", Map.of("input", Map.of("total", 2500)))
        .asBoolean()).isTrue();
    assertThat(sandbox.evaluate("input.total > 1000", Map.of("input", Map.of("total", 250)))
        .asBoolean()).isFalse();
  }

  @Test
  void handlesListsFromPreviousNodes() {
    var result = sandbox.evaluate("size(nodes.fetch.items)",
        Map.of("nodes", Map.of("fetch", Map.of("items", List.of(1, 2, 3)))));
    assertThat(result.asInt()).isEqualTo(3);
  }

  @Test
  void cannotReachTheFilesystem() {
    refuses("new('java.io.File', '/etc/passwd').exists()");
    refuses("java.nio.file.Files.readString(null)");
  }

  @Test
  void cannotReachTheNetwork() {
    refuses("new('java.net.Socket', 'attacker.test', 80)");
    refuses("new('java.net.URL', 'http://attacker.test').openStream()");
  }

  @Test
  void cannotStartAProcess() {
    refuses("java.lang.Runtime.getRuntime().exec('id')");
    refuses("new('java.lang.ProcessBuilder', 'sh').start()");
  }

  @Test
  void cannotUseReflectionToClimbOut() {
    refuses("''.getClass().forName('java.lang.Runtime')");
    refuses("input.getClass().getClassLoader()");
  }

  @Test
  void cannotReadSystemPropertiesOrTheEnvironment() {
    refuses("java.lang.System.getenv('ANTHROPIC_API_KEY')");
    refuses("java.lang.System.getProperty('user.home')");
  }

  @Test
  void cannotConstructAnythingAtAll() {
    refuses("new('java.lang.String', 'x')");
  }

  @Test
  void cannotLoopForever() {
    refuses("while (true) { 1 }");
    refuses("for (i : [1,2,3]) { i }");
  }

  @Test
  void cannotWriteBackIntoTheContext() {
    refuses("input = 'replaced'");
  }

  @Test
  void rejectsSyntaxErrorsWithAUsableMessage() {
    assertThatThrownBy(() -> sandbox.evaluate("input.(((", Map.of("input", Map.of())))
        .isInstanceOf(SandboxViolationException.class)
        .hasMessageContaining("not valid");
  }

  @Test
  void rejectsAnEmptyExpression() {
    assertThatThrownBy(() -> sandbox.evaluate("  ", Map.of()))
        .isInstanceOf(SandboxViolationException.class)
        .hasMessageContaining("no expression");
  }

  @Test
  void seesOnlyTheVariablesItIsGiven() {
    assertThatThrownBy(() -> sandbox.evaluate("somethingElse.value", Map.of("input", Map.of())))
        .isInstanceOf(SandboxViolationException.class);
  }

  // ── fmt: functions ─────────────────────────────────────────────────────

  @Test
  void formatsTextNumbersAndDatesWithoutAnAgent() {
    Map<String, Object> vars = Map.of("input", Map.of("name", "  priya sharma ",
        "amount", "1,23,456.789", "due", "2026-10-21T10:00:00+05:30",
        "items", List.of(Map.of("price", 10), Map.of("price", 32.5))));
    assertThat(sandbox.evaluate("fmt:titleCase(fmt:trim(input.name))", vars).asText())
        .isEqualTo("Priya Sharma");
    assertThat(sandbox.evaluate("fmt:round(input.amount, 2)", vars).asDouble()).isEqualTo(123456.79);
    assertThat(sandbox.evaluate("fmt:formatNumber(input.amount, 2)", vars).asText())
        .isEqualTo("123,456.79");
    assertThat(sandbox.evaluate("fmt:formatDate(input.due, 'dd MMM yyyy')", vars).asText())
        .isEqualTo("21 Oct 2026");
    assertThat(sandbox.evaluate("fmt:formatDate(fmt:addTime(input.due, 'P3D'), 'yyyy-MM-dd')", vars)
        .asText()).isEqualTo("2026-10-24");
    assertThat(sandbox.evaluate("fmt:sum(fmt:pluck(input.items, 'price'))", vars).asDouble())
        .isEqualTo(42.5);
    assertThat(sandbox.evaluate("fmt:lookup({'IN': 'India'}, 'IN', 'Unknown')", vars).asText())
        .isEqualTo("India");
  }

  @Test
  void theFormattersDoNotOpenAWayOut() {
    refuses("fmt:getClass().forName('java.lang.Runtime')");
    refuses("fmt:getClass().getName()");
    refuses("fmt:upper('x').getClass().forName('java.lang.Runtime')");
  }

  @Test
  void templatesFillPlaceholdersFromTheRun() {
    Map<String, Object> vars = Map.of("input", Map.of("name", "Priya", "total", 42),
        "vars", Map.of("tier", "gold"));
    assertThat(Templates.render("Hi {{ input.name }}, {{input.total * 2}} for {{ vars.tier }}{{ input.missing }}!",
        sandbox, vars)).isEqualTo("Hi Priya, 84 for gold!");
    assertThat(Templates.render("no placeholders", sandbox, vars)).isEqualTo("no placeholders");
  }
}
