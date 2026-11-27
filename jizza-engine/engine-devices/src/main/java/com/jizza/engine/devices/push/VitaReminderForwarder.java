package com.jizza.engine.devices.push;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.event.VitaReminderDue;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.persistence.entity.DeviceEntity;
import com.jizza.engine.persistence.repository.DeviceRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Wakes the person's own phones for a Vita medicine reminder. The push carries ids only (type,
 * person, slot time): never a name, a medicine or a value. The app reads the slot from
 * {@code GET /agent/v1/me/vita/today} and shows it itself.
 */
@Component
public class VitaReminderForwarder {

  private final DeviceRepository devices;
  private final PushDispatcher push;

  public VitaReminderForwarder(DeviceRepository devices, PushDispatcher push) {
    this.devices = devices;
    this.push = push;
  }

  @TransactionalEventListener(fallbackExecution = true)
  public void onReminder(VitaReminderDue event) {
    if (event.userId() == null || event.personId() == null || event.type() == null) return;
    ObjectNode data = data(event);
    for (DeviceEntity device : devices.findByStatus("active")) {
      if (!event.userId().equals(device.getOwnerUserId())) continue;
      if (!"android".equals(device.getPlatform()) && !"ios".equals(device.getPlatform())) continue;
      push.wake(device.getId(), data);
    }
  }

  static ObjectNode data(VitaReminderDue event) {
    ObjectNode data = Json.obj().put("type", event.type()).put("personId", event.personId().toString());
    if (event.dueAt() != null) data.put("dueAt", event.dueAt().toString());
    return data;
  }
}
