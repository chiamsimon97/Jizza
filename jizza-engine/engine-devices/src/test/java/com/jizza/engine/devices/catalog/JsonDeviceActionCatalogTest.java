package com.jizza.engine.devices.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.device.DeviceActionSpec;
import com.jizza.engine.api.tool.ToolSchemaValidator;
import com.jizza.engine.api.util.Json;
import java.io.InputStream;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class JsonDeviceActionCatalogTest {

  private static final List<String> NEW_BROWSER_ACTIONS = List.of(
      "browser.open@2", "browser.click@2", "browser.hover@1", "browser.setText@1",
      "browser.getText@1", "browser.setCheckbox@1", "browser.getCheckbox@1",
      "browser.selectItem@1", "browser.extractTable@1", "browser.selectRadio@1",
      "browser.getRadio@1", "browser.scroll@1", "browser.sendKeys@1", "browser.typeInto@1",
      "browser.exists@1", "browser.switchTo@1", "browser.refresh@1",
      "browser.getSelectors@1", "browser.pageInfo@1", "browser.forward@1", "browser.back@1",
      "browser.close@1", "browser.wait@1", "browser.screenshot@1",
      "browser.download@1", "browser.pickElement@1", "browser.record@1");

  private static final Set<String> ELEMENT_REQUIRED = Set.of(
      "browser.click@2", "browser.hover@1", "browser.setText@1", "browser.getText@1",
      "browser.setCheckbox@1", "browser.getCheckbox@1", "browser.selectItem@1",
      "browser.extractTable@1", "browser.typeInto@1", "browser.exists@1");

  private final JsonDeviceActionCatalog catalog = new JsonDeviceActionCatalog();

  @Test
  void everyEntryParsesAndEverySchemaHasAValidShape() throws Exception {
    assertThat(catalog.all()).isNotEmpty();
    try (InputStream in = getClass().getResourceAsStream(JsonDeviceActionCatalog.RESOURCE)) {
      JsonNode root = Json.mapper().readTree(in);
      sanityCheckSchema(root.path("schemas").path("locatorStrategy"), root, "$.schemas.locatorStrategy");
      sanityCheckSchema(root.path("schemas").path("locatorBundle"), root, "$.schemas.locatorBundle");
      sanityCheckSchema(root.path("schemas").path("secretRef"), root, "$.schemas.secretRef");
      for (JsonNode action : root.path("actions")) {
        sanityCheckSchema(action.path("input"), root, action.path("action").asText() + ".input");
        sanityCheckSchema(action.path("output"), root, action.path("action").asText() + ".output");
      }
    }

    for (DeviceActionSpec action : catalog.all()) {
      assertThat(ToolSchemaValidator.validate(action.input(), Json.obj())).isNotNull();
      assertThat(ToolSchemaValidator.validate(action.output(), Json.obj())).isNotNull();
    }
  }

  @Test
  void browserActionsHaveObjectOutputs() {
    assertThat(catalog.all().stream().filter(a -> a.action().startsWith("browser.")))
        .allSatisfy(action -> {
          assertThat(action.output()).isNotNull();
          assertThat(action.output().path("type").asText()).isEqualTo("object");
        });
  }

  @Test
  void newActionsExposeContractMetadataAndCommonInputs() {
    for (String ref : NEW_BROWSER_ACTIONS) {
      DeviceActionSpec action = action(ref);
      assertThat(action.capability()).as(ref).isEqualTo("browser");
      assertThat(action.conditions()).as(ref).containsExactly("site");
      assertThat(action.platforms()).as(ref)
          .containsExactly("browser", "windows", "macos", "linux");

      JsonNode properties = action.input().path("properties");
      assertThat(properties.has("session")).as(ref).isTrue();
      assertThat(properties.path("session").path("type").asText()).as(ref).isEqualTo("string");
      assertThat(properties.has("element")).as(ref).isTrue();
      assertThat(properties.path("timeoutMs").path("type").asText()).as(ref).isEqualTo("integer");
      assertThat(properties.path("timeoutMs").path("maximum").asInt()).as(ref).isEqualTo(120000);
      assertThat(properties.path("timeoutMs").path("default").asInt()).as(ref)
          .isEqualTo(ref.equals("browser.exists@1") ? 3000 : 15000);
      assertThat(properties.path("trusted").path("default").asBoolean()).as(ref).isFalse();
      assertThat(properties.path("screenshotOnError").path("default").asBoolean()).as(ref).isTrue();
      if (ELEMENT_REQUIRED.contains(ref)) {
        assertThat(action.input().path("required")).as(ref)
            .anySatisfy(required -> assertThat(required.asText()).isEqualTo("element"));
      }
    }
  }

  @Test
  void openLookupUsesLatestUnlessVersionIsExplicit() {
    assertThat(catalog.find("browser.open", 0)).get()
        .extracting(DeviceActionSpec::version).isEqualTo(2);
    assertThat(catalog.find("browser.open", 1)).get()
        .extracting(DeviceActionSpec::version).isEqualTo(1);
  }

  @Test
  void actionSpecificSchemasMatchTheWebAutomationContract() {
    JsonNode open = input("browser.open@2");
    assertThat(open.path("properties").path("in").has("default")).isFalse();
    assertEnum(open, "in", "newSession", "newTab", "currentTab");
    assertEnum(open, "waitUntil", "load", "domcontentloaded", "networkidle");

    JsonNode click = input("browser.click@2");
    assertEnum(click, "button", "left", "right", "middle");
    assertThat(click.path("properties").path("clickCount").path("enum"))
        .containsExactly(Json.mapper().getNodeFactory().numberNode(1),
            Json.mapper().getNodeFactory().numberNode(2));
    assertEnum(click, "waitAfter", "none", "navigation", "element");
    assertRef(click, "waitElement", "locatorBundle");

    assertDefault("browser.hover@1", "holdMs", 300);
    assertEnum(input("browser.getText@1"), "what", "text", "value", "attribute", "html");
    assertEnum(input("browser.setCheckbox@1"), "state", "checked", "unchecked", "toggle");

    JsonNode selectedItems = output("browser.selectItem@1").path("properties")
        .path("selected").path("items");
    assertObjectProperties(selectedItems, "value", "label");

    JsonNode table = input("browser.extractTable@1");
    assertEnum(table, "headerRow", "auto", "first", "none");
    assertDefault(table, "maxRows", 1000);
    assertThat(table.path("properties").path("maxRows").path("maximum").asInt()).isEqualTo(10000);
    assertDefault(table, "maxPages", 1);
    assertThat(table.path("properties").path("maxPages").path("maximum").asInt()).isEqualTo(50);
    assertEnum(table, "as", "rows", "csvFile");
    assertThat(output("browser.extractTable@1").path("properties").path("rows")
        .path("items").path("type").asText()).isEqualTo("object");

    assertThat(input("browser.selectRadio@1").path("properties").path("group")
        .path("type").asText()).isEqualTo("string");
    assertEnum(input("browser.selectRadio@1"), "by", "label", "value");
    assertObjectProperties(output("browser.selectRadio@1").path("properties").path("selected"),
        "value", "label");
    assertThat(input("browser.getRadio@1").path("properties").path("group")
        .path("type").asText()).isEqualTo("string");
    JsonNode radioSelected = output("browser.getRadio@1").path("properties")
        .path("selected").path("oneOf");
    assertObjectProperties(radioSelected.get(0), "value", "label");
    assertThat(radioSelected.get(1).path("type").asText()).isEqualTo("null");

    assertEnum(input("browser.scroll@1"), "to", "top", "bottom", "element", "by");
    assertDefault("browser.scroll@1", "maxScrolls", 20);
    JsonNode keys = input("browser.sendKeys@1").path("properties").path("keys").path("oneOf");
    assertThat(keys.get(0).path("type").asText()).isEqualTo("string");
    assertThat(keys.get(1).path("type").asText()).isEqualTo("array");
    assertDefault("browser.typeInto@1", "delayMs", 30);
    assertThat(input("browser.typeInto@1").path("properties").path("delayMs")
        .path("maximum").asInt()).isEqualTo(500);
    assertThat(input("browser.exists@1").path("properties").path("visibleOnly")
        .path("default").asBoolean()).isTrue();

    JsonNode switchTo = input("browser.switchTo@1");
    assertEnum(switchTo, "to", "tab", "frame", "mainFrame", "popup");
    JsonNode match = switchTo.path("properties").path("match").path("oneOf");
    assertObjectProperties(match.get(0), "titleContains", "urlContains", "index");
    assertThat(match.get(1).path("enum").get(0).asText()).isEqualTo("newest");
    assertEnum(input("browser.refresh@1"), "waitUntil",
        "load", "domcontentloaded", "networkidle");

    JsonNode pageInfo = input("browser.pageInfo@1");
    assertThat(pageInfo.path("properties").path("include").path("items").path("enum"))
        .extracting(JsonNode::asText)
        .containsExactly("tabs", "html", "screenshot", "meta");
    assertObjectProperties(output("browser.pageInfo@1"), "url", "title", "readyState", "lang",
        "scrollY", "tabs", "meta", "html", "screenshot");

    JsonNode close = input("browser.close@1");
    assertEnum(close, "what", "tab", "session");
    assertThat(close.path("properties").path("what").path("default").asText()).isEqualTo("session");
    assertEnum(input("browser.wait@1"), "for", "element", "elementGone", "navigation", "time");
    assertThat(input("browser.screenshot@1").path("properties").path("fullPage")
        .path("type").asText()).isEqualTo("boolean");
    assertRef(input("browser.download@1"), "element", "locatorBundle");
    assertObjectProperties(output("browser.download@1"), "file", "name", "size");
    assertObjectProperties(output("browser.pickElement@1"), "element", "preview", "url");
    assertDefault("browser.record@1", "maxMinutes", 30);
    assertEnum(output("browser.record@1"), "stoppedBy", "person", "timeout", "server");

    assertObjectProperties(output("browser.open@2"), "sessionId", "tabId", "url", "title");
    assertObjectProperties(output("browser.click@2"), "clicked", "navigated", "url");
    assertObjectProperties(output("browser.hover@1"), "hovered");
    assertObjectProperties(output("browser.setText@1"), "set", "value");
    assertObjectProperties(output("browser.getText@1"), "text");
    assertObjectProperties(output("browser.setCheckbox@1"), "checked", "changed");
    assertObjectProperties(output("browser.getCheckbox@1"), "checked");
    assertObjectProperties(output("browser.extractTable@1"),
        "columns", "rows", "rowCount", "pages", "file");
    assertObjectProperties(output("browser.getRadio@1"), "checked", "selected");
    assertObjectProperties(output("browser.scroll@1"), "scrollX", "scrollY", "atEnd");
    assertObjectProperties(output("browser.switchTo@1"), "tabId", "url", "title", "frame");
    assertObjectProperties(output("browser.forward@1"), "url", "title", "moved");
    assertObjectProperties(output("browser.back@1"), "url", "title", "moved");
  }

  @Test
  void resolvedBrowserSchemasRejectMissingChoicesBadUrlsAndEmptyLocators() {
    DeviceActionSpec setText = action("browser.setText@1");
    var emptyLocator = Json.obj();
    emptyLocator.putArray("strategies");
    assertThat(ToolSchemaValidator.validate(setText.input(),
        Json.obj().set("element", emptyLocator)).valid()).isFalse();

    DeviceActionSpec open = action("browser.open@2");
    assertThat(ToolSchemaValidator.validate(open.input(),
        Json.obj().put("url", "javascript:alert(1)")).valid()).isFalse();
    assertThat(ToolSchemaValidator.validate(open.input(),
        Json.obj().put("url", "https://example.com")).valid()).isTrue();
  }

  private DeviceActionSpec action(String ref) {
    int at = ref.lastIndexOf('@');
    return catalog.find(ref.substring(0, at), Integer.parseInt(ref.substring(at + 1)))
        .orElseThrow();
  }

  private JsonNode input(String ref) {
    return action(ref).input();
  }

  private JsonNode output(String ref) {
    return action(ref).output();
  }

  private void assertDefault(String ref, String property, int expected) {
    assertDefault(input(ref), property, expected);
  }

  private static void assertDefault(JsonNode schema, String property, int expected) {
    assertThat(schema.path("properties").path(property).path("default").asInt())
        .as(property + " default").isEqualTo(expected);
  }

  private static void assertEnum(JsonNode schema, String property, String... expected) {
    assertThat(schema.path("properties").path(property).path("enum"))
        .extracting(JsonNode::asText).containsExactly(expected);
  }

  private static void assertRef(JsonNode schema, String property, String definition) {
    JsonNode referenced = schema.path("properties").path(property);
    if (referenced.has("$ref")) {
      assertThat(referenced.path("$ref").asText()).endsWith("/" + definition);
    } else if ("locatorBundle".equals(definition)) {
      assertThat(referenced.path("type").asText()).isEqualTo("object");
      assertThat(referenced.path("properties").has("strategies")).isTrue();
    } else {
      assertThat(referenced.path("type").asText()).isEqualTo("object");
      assertThat(referenced.path("properties").has("login")).isTrue();
    }
  }

  private static void assertObjectProperties(JsonNode schema, String... expected) {
    assertThat(schema.path("type").asText()).isEqualTo("object");
    assertThat(schema.path("properties").fieldNames()).toIterable()
        .containsExactlyInAnyOrder(expected);
  }

  private static void sanityCheckSchema(JsonNode schema, JsonNode document, String path) {
    assertThat(schema.isObject()).as(path + " is an object").isTrue();
    if (schema.has("$ref")) {
      String ref = schema.path("$ref").asText();
      assertThat(ref).as(path + " ref").startsWith("device-actions.json#/schemas/");
      String name = ref.substring(ref.lastIndexOf('/') + 1);
      assertThat(document.path("schemas").has(name)).as(path + " resolved ref").isTrue();
    }
    if (schema.has("type")) {
      assertThat(schema.path("type").asText()).as(path + " type")
          .isIn("object", "array", "string", "integer", "number", "boolean", "null");
    }
    if (schema.has("properties")) {
      assertThat(schema.path("properties").isObject()).as(path + " properties").isTrue();
      schema.path("properties").fields()
          .forEachRemaining(field -> sanityCheckSchema(field.getValue(), document,
              path + ".properties." + field.getKey()));
    }
    if (schema.has("required")) {
      assertThat(schema.path("required").isArray()).as(path + " required").isTrue();
      if (schema.has("properties")) {
        schema.path("required").forEach(required ->
            assertThat(schema.path("properties").has(required.asText()))
                .as(path + " required property " + required.asText()).isTrue());
      }
    }
    if (schema.has("items")) {
      sanityCheckSchema(schema.path("items"), document, path + ".items");
    }
    for (String composition : List.of("oneOf", "anyOf", "allOf")) {
      if (schema.has(composition)) {
        assertThat(schema.path(composition).isArray()).as(path + "." + composition).isTrue();
        for (int i = 0; i < schema.path(composition).size(); i++) {
          sanityCheckSchema(schema.path(composition).get(i), document,
              path + "." + composition + "[" + i + "]");
        }
      }
    }
  }
}
