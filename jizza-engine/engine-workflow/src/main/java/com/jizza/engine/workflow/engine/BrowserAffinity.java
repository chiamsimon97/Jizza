package com.jizza.engine.workflow.engine;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

/** Restores browser affinity from a device step output, including checkpoint replay. */
public final class BrowserAffinity {

  private BrowserAffinity() {}

  public static void apply(RunContext context, JsonNode output) {
    if (output == null || !output.isObject()) return;
    JsonNode action = output.path("_browserAction");
    if (action.isTextual()) context.recordBrowserAction(action.asText());

    JsonNode clear = output.get("_browserClear");
    if (clear != null) {
      if (clear.asBoolean(false)) {
        context.setBrowser(null);
        return;
      }
      RunContext.Browser current = context.browser();
      UUID deviceId;
      try {
        deviceId = UUID.fromString(clear.path("deviceId").asText());
      } catch (IllegalArgumentException e) {
        return;
      }
      String sessionId = clear.path("sessionId").asText();
      context.forgetOwnedBrowser(deviceId, sessionId);
      if (current != null && deviceId.equals(current.deviceId())
          && sessionId.equals(current.sessionId())) {
        context.setBrowser(null);
        return;
      }
    }
    JsonNode browser = output.get("_browser");
    if (browser == null) return;
    if (!browser.path("deviceId").isTextual() || !browser.path("sessionId").isTextual()) return;
    try {
      RunContext.Browser current = context.browser();
      if (current != null && (!current.deviceId().toString().equals(browser.get("deviceId").asText())
          || !current.sessionId().equals(browser.get("sessionId").asText()))) {
        context.rememberOwnedBrowser(current);
      }
      context.setBrowser(new RunContext.Browser(
          UUID.fromString(browser.get("deviceId").asText()),
          browser.path("workspaceId").isTextual()
              ? UUID.fromString(browser.get("workspaceId").asText()) : null,
          browser.get("sessionId").asText(), output.path("_browserOpened").asBoolean(true)));
      context.browserSessionStarted();
    } catch (IllegalArgumentException ignored) {
      // A malformed device response must not corrupt otherwise resumable run state.
    }
  }
}
