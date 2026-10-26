package com.jizza.engine.workflow.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.error.SandboxViolationException;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.workflow.FormulaPreview;
import com.jizza.engine.api.workflow.functions.ScriptFunctionDraft;
import com.jizza.engine.api.workflow.functions.ScriptFunctionDto;
import com.jizza.engine.api.workflow.functions.ScriptFunctions;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Saved functions: run with typed inputs and a return type, and calling each other by name. */
class ScriptFunctionCallTest {

  private static final UUID OWNER = UUID.randomUUID();

  private final JexlSandbox sandbox = new JexlSandbox();
  private final Map<String, ScriptFunctionDto> saved = new HashMap<>();
  private final FunctionLookup lookup = saved::get;

  private ScriptFunctionDto save(String name, String inputs, String script, String returnType) {
    ScriptFunctionDto f = new ScriptFunctionDto(UUID.randomUUID(), OWNER, name, null, Json.read(inputs), script,
        returnType, Json.read("[]"), List.of(), null, null, ScriptFunctionDto.OWN);
    saved.put(name, f);
    return f;
  }

  private JsonNode run(ScriptFunctionDto f, Map<String, Object> args) {
    Map<String, JsonNode> given = new HashMap<>();
    args.forEach((k, v) -> given.put(k, Json.toNode(v)));
    return sandbox.runFunction(f, given, lookup);
  }

  @Test
  void aFunctionCallsAnotherByItsLinkNameWithItsInputsInOrder() {
    save("lineTotal", "[{\"name\":\"qty\",\"type\":\"number\"},{\"name\":\"price\",\"type\":\"number\"}]",
        "return qty * price;", "number");
    save("withTax", "[{\"name\":\"amount\",\"type\":\"number\"},{\"name\":\"rate\",\"type\":\"number\",\"default\":0.1}]",
        "return amount * (1 + rate);", "number");
    ScriptFunctionDto order = save("orderTotal", "[{\"name\":\"lines\",\"type\":\"list\",\"required\":true}]", """
        var total = 0;
        for (var line : lines) {
          total = total + fn.lineTotal(line.qty, line.price);
        }
        return {'subtotal': total, 'total': fn.withTax(total).round(2), 'count': lines.size()};
        """, "map");

    JsonNode out = run(order, Map.of("lines", List.of(Map.of("qty", 2, "price", 100), Map.of("qty", "1", "price", 50))));

    assertThat(out.path("subtotal").asDouble()).isEqualTo(250);
    assertThat(out.path("total").asDouble()).isEqualTo(275);
    assertThat(out.path("count").asInt()).isEqualTo(2);
  }

  @Test
  void inputsAndTheReturnTypeAreChecked() {
    ScriptFunctionDto half = save("half", "[{\"name\":\"n\",\"type\":\"number\",\"required\":true}]", "n / 2", "number");
    assertThat(run(half, Map.of("n", "10")).asDouble()).isEqualTo(5);
    assertThatThrownBy(() -> run(half, Map.of())).isInstanceOf(SandboxViolationException.class)
        .hasMessageContaining("fn.half needs the input n");
    assertThatThrownBy(() -> run(half, Map.of("n", "lots"))).hasMessageContaining("should be a number");

    ScriptFunctionDto word = save("word", "[]", "return 'seven';", "number");
    assertThatThrownBy(() -> run(word, Map.of())).hasMessageContaining("fn.word should return a number");
  }

  @Test
  void callsThatCannotWorkSayWhy() {
    save("one", "[{\"name\":\"x\",\"type\":\"any\"}]", "return x;", "any");
    ScriptFunctionDto tooMany = save("tooMany", "[]", "return fn.one(1, 2);", "any");
    assertThatThrownBy(() -> run(tooMany, Map.of())).hasMessageContaining("fn.one takes 1 input (x) but was given 2");

    ScriptFunctionDto missing = save("missing", "[]", "return fn.nothere(1);", "any");
    assertThatThrownBy(() -> run(missing, Map.of())).hasMessageContaining("There is no function called fn.nothere");

    save("ping", "[]", "return fn.pong();", "any");
    ScriptFunctionDto pong = save("pong", "[]", "return fn.ping();", "any");
    assertThatThrownBy(() -> run(pong, Map.of())).hasMessageContaining("A function can't call itself: fn.pong → fn.ping → fn.pong");
  }

  @Test
  void aCalledFunctionSeesOnlyItsOwnInputs() {
    save("peek", "[]", "return secret;", "any");
    ScriptFunctionDto outer = save("outer", "[{\"name\":\"secret\",\"type\":\"text\"}]", "return fn.peek();", "any");
    assertThatThrownBy(() -> run(outer, Map.of("secret", "s3cr3t"))).isInstanceOf(SandboxViolationException.class);
  }

  @Test
  void aCalledFunctionSharesTheCallersTimeLimit() {
    save("spin", "[]", "var n = 0; for (var i : 1..100000000) { for (var j : 1..100000000) { n = n + 1; } } return n;", "any");
    ScriptFunctionDto outer = save("outerSpin", "[]", "return fn.spin();", "any");
    assertThatThrownBy(() -> run(outer, Map.of())).isInstanceOf(SandboxViolationException.class)
        .hasMessageContaining("stopped");
  }

  @Test
  void aWorkflowStepRunsASavedFunctionWithItsInputsMappedFromTheRun() {
    ScriptFunctionDto order = save("orderTotal", "[{\"name\":\"lines\",\"type\":\"list\"},{\"name\":\"note\",\"type\":\"text\"}]",
        "var t = 0; for (var l : lines) { t = t + l.price; } return {'total': t, 'note': note.upper()};", "map");
    ScriptFunctionDto count = save("countOf", "[{\"name\":\"items\",\"type\":\"list\"}]", "items.size()", "number");
    ScriptFunctions store = new FakeFunctions(List.of(order, count));
    Map<String, Object> run = Map.of("nodes", Map.of("fetch", Map.of("lines", List.of(Map.of("price", 5), Map.of("price", 7)))));

    JsonNode map = ScriptStep.run(sandbox, store, OWNER, Json.read("{\"functionId\":\"" + order.id()
        + "\",\"inputs\":{\"lines\":\"{{ nodes.fetch.lines }}\",\"note\":\"ok\"}}"), run);
    assertThat(map.path("total").asInt()).isEqualTo(12);
    assertThat(map.path("note").asText()).isEqualTo("OK");

    JsonNode single = ScriptStep.run(sandbox, store, OWNER, Json.read("{\"functionId\":\"" + count.id()
        + "\",\"inputs\":{\"items\":\"{{ nodes.fetch.lines }}\"}}"), run);
    assertThat(single.path("result").asInt()).isEqualTo(2);

    assertThatThrownBy(() -> ScriptStep.run(sandbox, store, UUID.randomUUID(), Json.read("{\"functionId\":\"" + count.id()
        + "\",\"inputs\":{}}"), run)).hasMessageContaining("was deleted");
  }

  private record FakeFunctions(List<ScriptFunctionDto> all) implements ScriptFunctions {
    public List<ScriptFunctionDto> list(UUID userId) { return all; }
    public Optional<ScriptFunctionDto> find(UUID id) { return all.stream().filter(f -> f.id().equals(id)).findFirst(); }
    public Optional<ScriptFunctionDto> findByName(UUID userId, String name) {
      return all.stream().filter(f -> f.userId().equals(userId) && f.name().equals(name)).findFirst();
    }
    public ScriptFunctionDto create(UUID userId, ScriptFunctionDraft draft) { throw new UnsupportedOperationException(); }
    public ScriptFunctionDto update(UUID id, ScriptFunctionDraft draft) { throw new UnsupportedOperationException(); }
    public void delete(UUID id) { throw new UnsupportedOperationException(); }
    public FormulaPreview.Result test(UUID userId, ScriptFunctionDraft draft, JsonNode args) { throw new UnsupportedOperationException(); }
    public List<ScriptFunctionDto> publicFunctions() { return List.of(); }
    public java.util.Set<UUID> installed(UUID userId) { return java.util.Set.of(); }
    public boolean install(UUID userId, UUID id) { throw new UnsupportedOperationException(); }
    public boolean uninstall(UUID userId, UUID id) { throw new UnsupportedOperationException(); }
  }
}
