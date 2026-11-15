package com.jizza.engine.devices.broker;

import com.jizza.engine.api.event.DeviceHelpAnswered;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Passes the person's answer to a device's card on to the device, once the answer is saved. The
 * answer itself travels only in the event and the signed message to the device.
 */
@Component
public class DeviceHelpRelay {

  private final CommandBroker broker;

  public DeviceHelpRelay(CommandBroker broker) {
    this.broker = broker;
  }

  @TransactionalEventListener(fallbackExecution = true)
  public void onAnswered(DeviceHelpAnswered event) {
    broker.relayAnswer(event.userId(), event.deviceId(), event.commandId(), event.stop(),
        event.answer());
  }
}
