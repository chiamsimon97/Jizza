package com.jizza.engine.api.workflow;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Runs a formula from the workflow builder against sample data, the way a step would run it, so
 * the editor can show what it gives while it is being written. The engine evaluates it, so the
 * preview and a real run cannot disagree.
 */
public interface FormulaPreview {

  /**
   * @param formula  what the field holds
   * @param template true for a text field with {@code {{ … }}} placeholders, false for a formula
   * @param data     sample run data: {@code input}, {@code nodes}, {@code vars}; anything else is ignored
   */
  Result preview(String formula, boolean template, JsonNode data);

  /**
   * @param value what the formula gave, or null when it failed
   * @param type  text, number, boolean, list, map or empty
   * @param error why it failed, in words for the person writing it, or null
   */
  record Result(JsonNode value, String type, String error) {}
}
