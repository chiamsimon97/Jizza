package com.jizza.engine.workflow.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FormulaRewriterTest {

  private final JexlSandbox sandbox = new JexlSandbox();

  @Test
  void turnsChainedAndPlainCallsIntoFmtCalls() {
    assertThat(FormulaRewriter.toJexl("input.name.trim().upper()"))
        .isEqualTo("fmt:upper(fmt:trim(input.name))");
    assertThat(FormulaRewriter.toJexl("upper(input.name)")).isEqualTo("fmt:upper(input.name)");
    assertThat(FormulaRewriter.toJexl("nodes.a.body.price.round(2) > 10"))
        .isEqualTo("fmt:round(nodes.a.body.price, 2) > 10");
    assertThat(FormulaRewriter.toJexl("input.items[0].name.lower()"))
        .isEqualTo("fmt:lower(input.items[0].name)");
    assertThat(FormulaRewriter.toJexl("(input.a + input.b).round(1)"))
        .isEqualTo("fmt:round((input.a + input.b), 1)");
    assertThat(FormulaRewriter.toJexl("fmt:upper(input.a).trim()"))
        .isEqualTo("fmt:trim(fmt:upper(input.a))");
    assertThat(FormulaRewriter.toJexl("input.ok ? upper(input.a) : 'none'.upper()"))
        .isEqualTo("input.ok ? fmt:upper(input.a) : fmt:upper('none')");
  }

  @Test
  void leavesOlderExpressionsAlone() {
    for (String old : List.of("fmt:formatDate(input.date, 'dd MMM')", "size(nodes.fetch.items)",
        "input.user.name.toUpperCase()", "'upper(x)' + input.a", "input.count > 1",
        "{'a': input.a}")) {
      assertThat(FormulaRewriter.toJexl(old)).as(old).isEqualTo(old);
    }
  }

  @Test
  void runsChainedFormulas() {
    Map<String, Object> vars = Map.of("input", Map.of("name", "  asha rao ", "price", 1234.567,
        "items", List.of("b", "a", "c"), "date", "2026-10-22"));
    assertThat(sandbox.evaluate("input.name.trim().titleCase()", vars).asText()).isEqualTo("Asha Rao");
    assertThat(sandbox.evaluate("input.price.round(2)", vars).asDouble()).isEqualTo(1234.57);
    assertThat(sandbox.evaluate("input.items.sort().join(', ')", vars).asText()).isEqualTo("a, b, c");
    assertThat(sandbox.evaluate("input.name.trim().length() > 3 ? 'long' : 'short'", vars).asText())
        .isEqualTo("long");
    assertThat(sandbox.evaluate("input.date.addMonths(1).formatDate('dd MMM yyyy')", vars).asText())
        .isEqualTo("22 Nov 2026");
    assertThat(sandbox.evaluate("upper(input.name.trim().substring(0, 4))", vars).asText())
        .isEqualTo("ASHA");
  }
}
