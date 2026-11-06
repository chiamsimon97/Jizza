package com.jizza.engine.devices.push;

import com.jizza.engine.api.event.RunFailed;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.persistence.entity.DeviceEntity;
import com.jizza.engine.persistence.repository.DeviceRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Tells the person's own phones that a run failed. Like every Jizza push it carries only ids; the
 * app reads the run over HTTPS and shows the alert itself. Sent once the failure is saved, so the
 * app never wakes to a run that still looks like it is running.
 */
@Component
public class RunAlertForwarder {

  private final DeviceRepository devices;
  private final PushDispatcher push;

  public RunAlertForwarder(DeviceRepository devices, PushDispatcher push) {
    this.devices = devices;
    this.push = push;
  }

  @TransactionalEventListener(fallbackExecution = true)
  public void onRunFailed(RunFailed event) {
    if (event.userId() == null || event.runId() == null) return;
    for (DeviceEntity device : devices.findByStatus("active")) {
      if (!event.userId().equals(device.getOwnerUserId())) continue;
      if (!"android".equals(device.getPlatform()) && !"ios".equals(device.getPlatform())) continue;
      push.wake(device.getId(), Json.obj().put("type", "run_failed")
          .put("runId", event.runId().toString()));
    }
  }
}
