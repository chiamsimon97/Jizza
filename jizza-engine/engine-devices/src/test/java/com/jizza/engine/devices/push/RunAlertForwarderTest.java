package com.jizza.engine.devices.push;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jizza.engine.api.event.RunFailed;
import com.jizza.engine.persistence.entity.DeviceEntity;
import com.jizza.engine.persistence.repository.DeviceRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RunAlertForwarderTest {

  private final DeviceRepository devices = mock(DeviceRepository.class);
  private final PushDispatcher push = mock(PushDispatcher.class);
  private final RunAlertForwarder forwarder = new RunAlertForwarder(devices, push);

  @Test
  void onlyTheOwnersPhonesAreToldAndOnlyByIds() {
    UUID owner = UUID.randomUUID();
    DeviceEntity phone = device(owner, "android");
    DeviceEntity laptop = device(owner, "macos");
    DeviceEntity someoneElses = device(UUID.randomUUID(), "android");
    when(devices.findByStatus("active")).thenReturn(List.of(phone, laptop, someoneElses));
    UUID runId = UUID.randomUUID();

    forwarder.onRunFailed(new RunFailed(owner, runId, UUID.randomUUID(), "Invoices",
        "secret error text"));

    verify(push).wake(eq(phone.getId()), argThat(data ->
        data.path("type").asText().equals("run_failed")
            && data.path("runId").asText().equals(runId.toString())
            && !data.toString().contains("secret") && !data.toString().contains("Invoices")));
    verify(push, never()).wake(eq(laptop.getId()), any());
    verify(push, never()).wake(eq(someoneElses.getId()), any());
  }

  private static DeviceEntity device(UUID owner, String platform) {
    DeviceEntity d = new DeviceEntity();
    d.setId(UUID.randomUUID());
    d.setOwnerUserId(owner);
    d.setPlatform(platform);
    d.setStatus("active");
    return d;
  }
}
