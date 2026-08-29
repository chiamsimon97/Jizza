package com.jizza.workflow.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jizza.common.error.SandboxViolationException;
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
}
