package com.jizza.engine.api.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.jizza.engine.api.util.Json;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolSchemaValidatorTest {

  private final com.fasterxml.jackson.databind.JsonNode schema = JsonSchemas.object(
      Map.of("query", JsonSchemas.string("search text"), "k", JsonSchemas.integer("result count")),
      List.of("query"));

  @Test
  void acceptsAValidCall() {
    var args = Json.obj().put("query", "blood pressure").put("k", 5);
    assertThat(ToolSchemaValidator.validate(schema, args).valid()).isTrue();
  }

  @Test
  void rejectsMissingRequiredProperty() {
    var result = ToolSchemaValidator.validate(schema, Json.obj().put("k", 3));
    assertThat(result.valid()).isFalse();
    assertThat(result.message()).contains("missing required property 'query'");
  }

  @Test
  void rejectsWrongType() {
    var result = ToolSchemaValidator.validate(schema, Json.obj().put("query", 42));
    assertThat(result.valid()).isFalse();
    assertThat(result.message()).contains("expected string");
  }

  @Test
  void rejectsUnexpectedPropertyWhenAdditionalPropertiesIsFalse() {
    var args = Json.obj().put("query", "x").put("sneaky", "value");
    assertThat(ToolSchemaValidator.validate(schema, args).valid()).isFalse();
  }

  @Test
  void enforcesCompositionPatternsAndArrayBounds() {
    var composed = Json.obj();
    var textChoice = Json.obj();
    textChoice.putArray("required").add("text");
    var secretChoice = Json.obj();
    secretChoice.putArray("required").add("secretRef");
    composed.putArray("oneOf").add(textChoice).add(secretChoice);
    var properties = composed.putObject("properties");
    properties.set("text", Json.obj().put("type", "string").put("pattern", "^https?://"));
    properties.set("secretRef", Json.obj().put("type", "object"));
    properties.set("items", Json.obj().put("type", "array").put("minItems", 1)
        .put("maxItems", 2).put("uniqueItems", true)
        .set("items", Json.obj().put("type", "string")));

    var badUrl = Json.obj().put("text", "javascript:alert(1)");
    badUrl.putArray("items").add("a");
    assertThat(ToolSchemaValidator.validate(composed, badUrl).valid()).isFalse();
    ArrayNode tooMany = Json.mapper().createArrayNode().add("a").add("a").add("b");
    assertThat(ToolSchemaValidator.validate(composed,
        Json.obj().put("text", "https://example.com").set("items", tooMany)).valid()).isFalse();
    var valid = Json.obj().put("text", "https://example.com");
    valid.putArray("items").add("a");
    assertThat(ToolSchemaValidator.validate(composed, valid).valid()).isTrue();
  }
}
