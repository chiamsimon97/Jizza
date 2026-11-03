package com.jizza.engine.api.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.util.Json;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ToolInputSchemasTest {

  private static List<String> required(JsonNode schema) {
    List<String> out = new ArrayList<>();
    schema.path("required").forEach(n -> out.add(n.asText()));
    return out;
  }

  @Test
  void leavesAPlainObjectSchemaAlone() {
    JsonNode schema = Json.read("""
        {"type":"object","properties":{"q":{"type":"string","anyOf":[{"minLength":1}]}},
         "required":["q"]}""");
    assertThat(ToolInputSchemas.needsChange(schema)).isFalse();
    assertThat(ToolInputSchemas.forModel(schema)).isSameAs(schema);
  }

  @Test
  void flattensATopLevelOneOfIntoOneObject() {
    JsonNode schema = Json.read("""
        {"type":"object","properties":{"exchange":{"type":"string"}},"required":["exchange"],
         "oneOf":[
           {"title":"By symbol","properties":{"symbol":{"type":"string"}},"required":["symbol"]},
           {"title":"By token","properties":{"token":{"type":"integer"}},"required":["token"]}]}""");
    JsonNode out = ToolInputSchemas.forModel(schema);
    assertThat(out.has("oneOf")).isFalse();
    assertThat(out.path("type").asText()).isEqualTo("object");
    assertThat(out.path("properties").has("symbol")).isTrue();
    assertThat(out.path("properties").has("token")).isTrue();
    assertThat(required(out)).containsExactly("exchange");
    assertThat(out.path("description").asText())
        .contains("By symbol (symbol)").contains("By token (token)");
    assertThat(schema.has("oneOf")).as("original untouched").isTrue();
  }

  @Test
  void mergesAllOfAndFollowsLocalRefs() {
    JsonNode schema = Json.read("""
        {"$defs":{"Base":{"type":"object","properties":{"id":{"type":"string"}},"required":["id"]}},
         "allOf":[{"$ref":"#/$defs/Base"},
                  {"type":"object","properties":{"note":{"type":"string"}},"required":["note"]}]}""");
    JsonNode out = ToolInputSchemas.forModel(schema);
    assertThat(out.has("allOf")).isFalse();
    assertThat(out.path("type").asText()).isEqualTo("object");
    assertThat(out.path("properties").has("id")).isTrue();
    assertThat(out.path("properties").has("note")).isTrue();
    assertThat(required(out)).containsExactlyInAnyOrder("id", "note");
  }

  @Test
  void anyOfWithoutTypeAndNonObjectBranchesStillGivesAnObject() {
    JsonNode schema = Json.read("""
        {"anyOf":[{"type":"string"},{"type":"null"}],"not":{"required":["x"]}}""");
    JsonNode out = ToolInputSchemas.forModel(schema);
    assertThat(out.path("type").asText()).isEqualTo("object");
    assertThat(out.path("properties").isObject()).isTrue();
    assertThat(out.has("anyOf")).isFalse();
    assertThat(out.has("not")).isFalse();
  }

  @Test
  void aMissingSchemaBecomesAnEmptyObject() {
    JsonNode out = ToolInputSchemas.forModel(null);
    assertThat(out.path("type").asText()).isEqualTo("object");
    assertThat(out.path("properties").size()).isZero();
  }
}
