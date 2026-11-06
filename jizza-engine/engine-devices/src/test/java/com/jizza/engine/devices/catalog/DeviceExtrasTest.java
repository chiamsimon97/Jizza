package com.jizza.engine.devices.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.jizza.engine.api.device.DeviceActionSpec;
import com.jizza.engine.api.enums.ActionType;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.util.Json;
import org.junit.jupiter.api.Test;

class DeviceExtrasTest {

  @Test
  void aToolIsKeptWithItsSchemasAndJudgedAsTheRiskiestKind() {
    ArrayNode tools = DeviceExtras.tools(Json.read("""
        [{"action":"x-acme.export","version":3,"title":"Export","timeoutSec":999999,
          "input":{"type":"object","properties":{"file":{"type":"string"}}}}]"""));
    JsonNode manifest = Json.obj().set("tools", tools);
    DeviceActionSpec spec = DeviceExtras.spec(manifest, null, "x-acme.export").orElseThrow();
    assertThat(spec.version()).isEqualTo(3);
    assertThat(spec.capability()).isEqualTo("x-acme");
    assertThat(spec.actionType()).isEqualTo(ActionType.MONEY);
    assertThat(spec.defaultTimeoutSec()).isEqualTo(86_400);
    assertThat(spec.input().path("properties").has("file")).isTrue();
    assertThat(DeviceExtras.spec(manifest, null, "x-acme.other")).isEmpty();
  }

  @Test
  void theOwnersTypeReplacesMovingMoney() {
    JsonNode manifest = Json.obj().set("tools", DeviceExtras.tools(Json.read("[{\"action\":\"x-a.read\"}]")));
    assertThat(DeviceExtras.spec(manifest, Json.obj().put("x-a.read", "READ"), "x-a.read")
        .orElseThrow().actionType()).isEqualTo(ActionType.READ);
    assertThat(DeviceExtras.spec(manifest, Json.obj().put("x-a.read", "NONSENSE"), "x-a.read")
        .orElseThrow().actionType()).isEqualTo(ActionType.MONEY);
  }

  @Test
  void aToolWithoutAnInputSchemaTakesAnyObject() {
    ArrayNode tools = DeviceExtras.tools(Json.read("[{\"action\":\"x-acme.ping\"}]"));
    assertThat(tools.get(0).path("input").path("type").asText()).isEqualTo("object");
    assertThat(tools.get(0).path("version").asInt()).isEqualTo(1);
  }

  @Test
  void badToolsAreRefusedByName() {
    assertThatThrownBy(() -> DeviceExtras.tools(Json.read("[{\"action\":\"acme.export\"}]")))
        .isInstanceOf(ValidationException.class).hasMessageContaining("x-acme.export");
    assertThatThrownBy(() -> DeviceExtras.tools(Json.read(
        "[{\"action\":\"x-a.b\"},{\"action\":\"x-a.b\"}]")))
        .hasMessageContaining("twice");
    assertThatThrownBy(() -> DeviceExtras.tools(Json.read(
        "[{\"action\":\"x-a.b\",\"input\":\"text\"}]")))
        .hasMessageContaining("JSON Schema");
  }

  @Test
  void itemsAreCleanedAndKindsChecked() {
    JsonNode items = DeviceExtras.items(Json.read("""
        {"recordings":[{"id":"r1","name":"Invoices"},{"name":"no id"},{"id":"r2"}]}"""));
    assertThat(items.path("recordings")).hasSize(2);
    assertThat(items.path("recordings").get(1).path("name").asText()).isEqualTo("r2");
    assertThatThrownBy(() -> DeviceExtras.items(Json.read("{\"Bad Kind\":[]}")))
        .isInstanceOf(ValidationException.class);
  }
}
