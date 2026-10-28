package com.jizza.engine.devices.broker;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.devices.catalog.JsonDeviceActionCatalog;
import com.jizza.engine.persistence.entity.DeviceEntity;
import org.junit.jupiter.api.Test;

/** A batch's steps meet the same checks as the same steps sent one by one. */
class BatchCheckTest {

  private final JsonDeviceActionCatalog catalog = new JsonDeviceActionCatalog();

  private static DeviceEntity browser(String... actions) {
    DeviceEntity device = new DeviceEntity();
    device.setName("Chrome on MacBook");
    ArrayNode declared = Json.mapper().createArrayNode();
    for (String a : actions) declared.add(a);
    device.setActions(declared);
    ObjectNode manifest = Json.obj();
    manifest.putObject("capabilities").putObject("browser").putArray("allowedSites")
        .add("*.example.com");
    device.setManifest(manifest);
    device.setBlockedActions(Json.mapper().createArrayNode());
    return device;
  }

  private static ObjectNode batch(String action, int version, ObjectNode input) {
    ObjectNode batch = Json.obj();
    ObjectNode step = batch.putArray("steps").addObject().put("key", "s1").put("action", action)
        .put("version", version);
    step.set("input", input);
    return batch;
  }

  private static ObjectNode click() {
    ObjectNode input = Json.obj();
    input.putObject("element").putArray("strategies").addObject().put("by", "text")
        .put("value", "Save");
    return input;
  }

  @Test
  void stepsTheBrowserRunsWithValidInputsPass() {
    DeviceEntity device = browser("browser.open@2", "browser.click@2", "browser.runSteps@1");
    ObjectNode batch = batch("browser.open", 2, Json.obj().put("url", "https://app.example.com"));
    ((ArrayNode) batch.get("steps")).addObject().put("key", "s2").put("action", "browser.click")
        .put("version", 2).set("input", click());

    assertThatCode(() -> CommandBroker.checkBatch(catalog, device, batch)).doesNotThrowAnyException();
  }

  @Test
  void aStepTheBrowserDoesNotRunIsRefused() {
    DeviceEntity device = browser("browser.open@2", "browser.runSteps@1");
    assertThatThrownBy(() -> CommandBroker.checkBatch(catalog, device, batch("browser.click", 2, click())))
        .isInstanceOf(ValidationException.class).hasMessageContaining("does not run browser.click@2");
  }

  @Test
  void aStepOutsideTheDevicesSitesIsRefused() {
    DeviceEntity device = browser("browser.open@2", "browser.runSteps@1");
    assertThatThrownBy(() -> CommandBroker.checkBatch(catalog, device,
        batch("browser.open", 2, Json.obj().put("url", "https://evil.test"))))
        .isInstanceOf(ValidationException.class).hasMessageContaining("would refuse");
  }

  @Test
  void aTurnedOffStepBadInputsAndNestedOrDesignTimeActionsAreRefused() {
    DeviceEntity device = browser("browser.click@2", "browser.record@1", "browser.runSteps@1");
    assertThatThrownBy(() -> CommandBroker.checkBatch(catalog, device,
        batch("browser.click", 2, Json.obj())))
        .isInstanceOf(ValidationException.class).hasMessageContaining("not right");
    assertThatThrownBy(() -> CommandBroker.checkBatch(catalog, device,
        batch("browser.record", 1, Json.obj().put("recordingId", "r"))))
        .isInstanceOf(ValidationException.class).hasMessageContaining("cannot run as part of a batch");
    assertThatThrownBy(() -> CommandBroker.checkBatch(catalog, device,
        batch("browser.runSteps", 1, Json.obj())))
        .isInstanceOf(ValidationException.class).hasMessageContaining("cannot run as part of a batch");
    device.setBlockedActions(Json.mapper().createArrayNode().add("browser.click"));
    assertThatThrownBy(() -> CommandBroker.checkBatch(catalog, device,
        batch("browser.click", 2, click())))
        .isInstanceOf(ValidationException.class).hasMessageContaining("turned off");
  }
}
