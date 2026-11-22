package com.jizza.engine.devices.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jizza.engine.api.device.DeviceActionCatalog;
import com.jizza.engine.api.device.DeviceActionSpec;
import com.jizza.engine.api.enums.ActionType;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.devices.broker.CommandBroker;
import com.jizza.engine.persistence.entity.AgentEntity;
import com.jizza.engine.persistence.entity.DeviceEntity;
import com.jizza.engine.persistence.repository.AgentRepository;
import com.jizza.engine.persistence.repository.DeviceRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DeviceToolProviderTest {

  private final UUID userId = UUID.randomUUID();
  private final UUID jizzaId = UUID.randomUUID();
  private final DeviceToolProvider provider;

  DeviceToolProviderTest() {
    AgentRepository agents = mock(AgentRepository.class);
    DeviceRepository devices = mock(DeviceRepository.class);
    DeviceActionCatalog catalog = mock(DeviceActionCatalog.class);
    AgentEntity jizza = new AgentEntity();
    jizza.setId(jizzaId);
    jizza.setUserId(userId);
    jizza.setOrchestrator(true);
    when(agents.findById(jizzaId)).thenReturn(Optional.of(jizza));
    DeviceEntity chrome = new DeviceEntity();
    chrome.setId(UUID.randomUUID());
    chrome.setWorkspaceId(UUID.randomUUID());
    chrome.setName("Chrome");
    chrome.setPlatform("browser");
    var actions = Json.mapper().createArrayNode();
    actions.add("browser.click@2").add("browser.getText@1").add("notify.show@1");
    chrome.setActions(actions);
    when(devices.findByOwnerUserIdAndStatusOrderByCreatedAtAsc(userId, "active"))
        .thenReturn(List.of(chrome));
    add(catalog, "browser.click", ActionType.UPDATE);
    add(catalog, "browser.getText", ActionType.READ);
    add(catalog, "notify.show", ActionType.UPDATE);
    provider = new DeviceToolProvider(agents, devices, catalog, mock(CommandBroker.class));
  }

  private static void add(DeviceActionCatalog catalog, String action, ActionType type) {
    when(catalog.resolve(action, 0)).thenReturn(Optional.of(new DeviceActionSpec(action, 1,
        action.startsWith("browser") ? "browser" : "notify", type, action, "", null, null,
        List.of(), List.of(), 60)));
  }

  @Test
  void aBrowserActionThatChangesAPageAlwaysAsksButReadingDoesNot() {
    assertThat(provider.alwaysAsks(jizzaId, "device_chrome__browser_click")).isTrue();
    assertThat(provider.alwaysAsks(jizzaId, "device_chrome__browser_getText")).isFalse();
  }

  @Test
  void otherDeviceActionsAreLeftToTheGuardrails() {
    assertThat(provider.alwaysAsks(jizzaId, "device_chrome__notify_show")).isFalse();
    assertThat(provider.alwaysAsks(jizzaId, "something_else")).isFalse();
  }

  @Test
  void aDeviceIsJizzasUntilItIsGivenToOtherAgents() {
    AgentEntity jizza = agent("jizza", true);
    AgentEntity kai = agent("finance", false);
    DeviceEntity laptop = new DeviceEntity();
    assertThat(DeviceToolProvider.givenTo(laptop, jizza)).isTrue();
    assertThat(DeviceToolProvider.givenTo(laptop, kai)).isFalse();

    laptop.setAgentKeys(Json.mapper().createArrayNode().add("finance"));
    assertThat(DeviceToolProvider.givenTo(laptop, jizza)).isFalse();
    assertThat(DeviceToolProvider.givenTo(laptop, kai)).isTrue();

    laptop.setAgentKeys(Json.mapper().createArrayNode().add("*"));
    assertThat(DeviceToolProvider.givenTo(laptop, jizza)).isTrue();
    assertThat(DeviceToolProvider.givenTo(laptop, kai)).isTrue();
  }

  private static AgentEntity agent(String key, boolean orchestrator) {
    AgentEntity a = new AgentEntity();
    a.setKey(key);
    a.setOrchestrator(orchestrator);
    return a;
  }
}
