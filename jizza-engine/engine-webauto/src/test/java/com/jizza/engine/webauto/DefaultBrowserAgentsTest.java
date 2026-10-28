package com.jizza.engine.webauto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jizza.engine.api.authz.Access;
import com.jizza.engine.api.device.DeviceDto;
import com.jizza.engine.api.device.DevicePlatform;
import com.jizza.engine.api.device.DeviceStatus;
import com.jizza.engine.api.device.Devices;
import com.jizza.engine.api.util.Json;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DefaultBrowserAgentsTest {

  @Test
  void listsOnlyVisibleDevicesThatDeclaredBrowserActions() {
    Devices devices = mock(Devices.class);
    Access access = new Access(UUID.randomUUID(), UUID.randomUUID());
    DeviceDto browser = device(access, "Chrome", List.of("browser.open@2"));
    DeviceDto laptop = device(access, "Laptop", List.of("system.info@1"));
    when(devices.list(access.workspaceId(), access.userId())).thenReturn(List.of(browser, laptop));

    var listed = new DefaultBrowserAgents(devices).list(access);

    assertThat(listed).singleElement().satisfies(found -> {
      assertThat(found.id()).isEqualTo(browser.id());
      assertThat(found.workspaceId()).isEqualTo(access.workspaceId());
      assertThat(found.family()).isEqualTo("chrome");
      assertThat(found.version()).isEqualTo("131");
      assertThat(found.allowedSites()).containsExactly("example.com");
      assertThat(found.sessions()).isEqualTo(3);
    });
  }

  private static DeviceDto device(Access access, String name, List<String> actions) {
    var capabilities = Json.obj();
    capabilities.putObject("browser").put("family", "chrome").put("version", "131")
        .put("sessions", 3).putArray("allowedSites").add("example.com");
    return new DeviceDto(UUID.randomUUID(), access.workspaceId(), access.userId(), name,
        DevicePlatform.BROWSER, null, null, 1, DeviceStatus.ACTIVE, List.of(), "browser", true,
        "idle", capabilities, actions, false, List.of(), Instant.now(), Instant.now());
  }
}
