package com.jizza.engine.webauto;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.authz.Access;
import com.jizza.engine.api.device.DeviceDto;
import com.jizza.engine.api.device.Devices;
import com.jizza.engine.api.webauto.BrowserAgents;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Finds browser-capable devices from the caller's visible device list so design-time pickers obey
 * the same workspace and ownership boundary as Settings.
 */
@Service
public class DefaultBrowserAgents implements BrowserAgents {

  private final Devices devices;

  public DefaultBrowserAgents(Devices devices) {
    this.devices = devices;
  }

  @Override
  public List<Browser> list(Access access) {
    return devices.list(access.workspaceId(), access.userId()).stream()
        .filter(this::declaresBrowserAction)
        .map(this::browser)
        .toList();
  }

  private boolean declaresBrowserAction(DeviceDto device) {
    return device.actions() != null
        && device.actions().stream().anyMatch(action -> action.startsWith("browser."));
  }

  private Browser browser(DeviceDto device) {
    JsonNode capability = device.capabilities() == null
        ? null : device.capabilities().path("browser");
    List<String> allowedSites = new ArrayList<>();
    if (capability != null) capability.path("allowedSites").forEach(site -> allowedSites.add(site.asText()));
    return new Browser(device.id(), device.workspaceId(), device.name(), device.online(),
        text(capability, "family"), text(capability, "version"), List.copyOf(allowedSites),
        capability != null && capability.has("sessions") ? capability.path("sessions").asInt() : null,
        device.lastSeenAt());
  }

  private static String text(JsonNode node, String field) {
    return node != null && node.hasNonNull(field) ? node.path(field).asText() : null;
  }
}
