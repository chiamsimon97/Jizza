package com.jizza.engine.devices.push;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.event.InstructionRequired;
import com.jizza.engine.api.event.PermissionRequested;
import com.jizza.engine.api.event.WorkflowReviewRequested;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.devices.crypto.ServerKeys;
import com.jizza.engine.devices.gateway.DeviceHub;
import com.jizza.engine.devices.protocol.Canonical;
import com.jizza.engine.devices.protocol.Protocol;
import com.jizza.engine.persistence.entity.DeviceEntity;
import com.jizza.engine.persistence.repository.DeviceRepository;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Sends what needs the person to their phones: an {@code inbox} message the app shows and can
 * answer, and a push that wakes it. Only phones the person paired themselves get it.
 */
@Component
public class InboxForwarder {

  private final DeviceRepository devices;
  private final DeviceHub hub;
  private final PushDispatcher push;
  private final ServerKeys keys;

  public InboxForwarder(DeviceRepository devices, DeviceHub hub, PushDispatcher push,
                        ServerKeys keys) {
    this.devices = devices;
    this.hub = hub;
    this.push = push;
    this.keys = keys;
  }

  @EventListener
  public void onPermission(PermissionRequested event) {
    forward(event.userId(), event.notificationId(), "permission_request", event.body());
  }

  @EventListener
  public void onInstruction(InstructionRequired event) {
    forward(event.userId(), event.notificationId(), "instruction_required", event.body());
  }

  @EventListener
  public void onReview(WorkflowReviewRequested event) {
    forward(event.userId(), event.notificationId(), "workflow_review", null);
  }

  void forward(UUID userId, UUID notificationId, String kind, String text) {
    if (userId == null || notificationId == null) return;
    for (DeviceEntity device : devices.findByStatus("active")) {
      if (!userId.equals(device.getOwnerUserId())) continue;
      if (!"android".equals(device.getPlatform()) && !"ios".equals(device.getPlatform())) continue;
      ObjectNode body = Json.obj().put("notificationId", notificationId.toString())
          .put("deviceId", device.getId().toString()).put("kind", kind);
      if (text != null) body.put("text", text.length() > 500 ? text.substring(0, 500) : text);
      ObjectNode env = Protocol.envelope(Protocol.INBOX, device.getId(), body, null);
      env.put("sig", keys.sign(Canonical.of(body)));
      if (!hub.deliver(device.getId(), env)) {
        push.wake(device.getId(), Json.obj().put("type", "inbox")
            .put("notificationId", notificationId.toString()));
      }
    }
  }
}
