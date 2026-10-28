package com.jizza.engine.api.device;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DevicePlatformTest {

  @ParameterizedTest
  @ValueSource(strings = {"browser", "chrome", "chromium", "edge"})
  void browserNamesUseTheBrowserPlatform(String name) {
    assertThat(DevicePlatform.from(name)).isEqualTo(DevicePlatform.BROWSER);
    assertThat(DevicePlatform.from(name).phone()).isFalse();
  }

  @Test
  void browserKeyStorageHasTheSameWarningAsFileStorage() {
    DeviceDto browser = new DeviceDto(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
        "Chrome", DevicePlatform.BROWSER, null, null, 1, DeviceStatus.ACTIVE, List.of(),
        "browser", false, null, null, List.of(), false, List.of(), null, null);
    assertThat(browser.weakKeyStorage()).isTrue();
  }
}
