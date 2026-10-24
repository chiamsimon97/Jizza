package com.jizza.engine.workflow.sandbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.error.SandboxViolationException;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.workflow.functions.ScriptFunctionDto;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code fn} namespace a script sees: {@code fn.total(lines, 0.2)}, which
 * {@link FormulaRewriter} turns into {@code fn:call('total', lines, 0.2)}, runs the person's
 * function named total with its inputs in the order it declares them.
 *
 * <p>The called function runs on the caller's thread, inside the caller's time limit, with only its
 * own inputs in scope. A function cannot call itself, directly or through others, and calls nest at
 * most {@link #MAX_DEPTH} deep. {@link #call} is the one public method: it is all a script can reach.
 */
public final class FunctionCalls {

  /** How deep calls may nest, counting the function the run started with. */
  public static final int MAX_DEPTH = 8;

  private final JexlSandbox sandbox;
  private final FunctionLookup lookup;
  private final Deque<String> stack;

  FunctionCalls(JexlSandbox sandbox, FunctionLookup lookup, Deque<String> stack) {
    this.sandbox = sandbox;
    this.lookup = lookup == null ? FunctionLookup.NONE : lookup;
    this.stack = stack == null ? new ArrayDeque<>() : stack;
  }

  public Object call(String name, Object... args) {
    ScriptFunctionDto f = lookup.find(name);
    if (f == null) throw new SandboxViolationException("There is no function called fn." + name);
    if (stack.contains(name)) {
      List<String> path = new java.util.ArrayList<>(stack);
      java.util.Collections.reverse(path);
      path.add(name);
      throw new SandboxViolationException("A function can't call itself: "
          + String.join(" → ", path.stream().map(n -> "fn." + n).toList()));
    }
    if (stack.size() >= MAX_DEPTH) {
      throw new SandboxViolationException("Functions call each other more than " + MAX_DEPTH + " deep");
    }
    List<String> names = ScriptFunctionRunner.inputNames(f);
    Object[] given = args == null ? new Object[0] : args;
    if (given.length > names.size()) {
      throw new SandboxViolationException("fn." + name + " takes " + names.size()
          + (names.size() == 1 ? " input" : " inputs") + (names.isEmpty() ? "" : " (" + String.join(", ", names) + ")")
          + " but was given " + given.length);
    }
    Map<String, JsonNode> named = new LinkedHashMap<>();
    for (int i = 0; i < given.length; i++) named.put(names.get(i), Json.toNode(given[i]));
    stack.push(name);
    try {
      JsonNode result = sandbox.invokeInline(f, named, this);
      return result == null || result.isNull() ? null : Json.mapper().convertValue(result, Object.class);
    } finally {
      stack.pop();
    }
  }
}
