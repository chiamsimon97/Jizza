package com.jizza.engine.devices.push;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jizza.engine.api.event.VitaReminderDue;
import com.jizza.engine.persistence.entity.DeviceEntity;
import com.jizza.engine.persistence.repository.DeviceRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VitaReminderForwarderTest {

  private final DeviceRepository devices = mock(DeviceRepository.class);
  private final PushDispatcher push = mock(PushDispatcher.class);
  private final VitaReminderForwarder forwarder = new VitaReminderForwarder(devices, push);

  @Test
  void onlyTheRecipientsPhonesAreWokenAndOnlyWithIds() {
    UUID owner = UUID.randomUUID();
    DeviceEntity phone = device(owner, "ios");
    DeviceEntity laptop = device(owner, "windows");
    DeviceEntity someoneElses = device(UUID.randomUUID(), "android");
    when(devices.findByStatus("active")).thenReturn(List.of(phone, laptop, someoneElses));
    UUID person = UUID.randomUUID();
    Instant due = Instant.parse("2026-12-10T14:30:00Z");

    forwarder.onReminder(new VitaReminderDue(owner, "vita_slot", person, due));

    verify(push).wake(eq(phone.getId()), argThat(data -> data.size() == 3
        && data.path("type").asText().equals("vita_slot")
        && data.path("personId").asText().equals(person.toString())
        && data.path("dueAt").asText().equals(due.toString())));
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
