package com.jizza.common.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.jizza.common.util.Json;
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
}
