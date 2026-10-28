package com.jizza.engine.devices.events;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.enums.WorkflowStatus;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.webauto.RecordingIntake;
import com.jizza.engine.api.workflow.WorkflowEngine;
import com.jizza.engine.persistence.entity.DeviceEntity;
import com.jizza.engine.persistence.repository.DeviceRepository;
import com.jizza.engine.persistence.repository.WorkflowRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class DeviceEventIntakeTest {

  @SuppressWarnings("unchecked")
  @Test
  void recordingEventsGoToTheRecordingServiceAndNeverStartAWorkflow() {
    UUID deviceId = UUID.randomUUID();
    UUID owner = UUID.randomUUID();
    DeviceEntity device = new DeviceEntity();
    device.setId(deviceId);
    device.setName("Chrome");
    device.setStatus("active");
    device.setOwnerUserId(owner);
    DeviceRepository devices = mock(DeviceRepository.class);
    when(devices.findById(deviceId)).thenReturn(Optional.of(device));
    WorkflowRepository workflows = mock(WorkflowRepository.class);
    WorkflowEngine engine = mock(WorkflowEngine.class);
    RecordingIntake recordings = mock(RecordingIntake.class);
    ObjectProvider<WorkflowEngine> engines = mock(ObjectProvider.class);
    when(engines.getIfAvailable()).thenReturn(engine);
    ObjectProvider<RecordingIntake> intake = mock(ObjectProvider.class);
    when(intake.getIfAvailable()).thenReturn(recordings);
    var service = new DeviceEventIntake(mock(ObjectProvider.class), devices, workflows, engines,
        mock(AuditLog.class), intake);

    var data = Json.obj().put("recordingId", UUID.randomUUID().toString());
    var payload = Json.obj().put("deviceId", deviceId.toString())
        .put("name", "browser.recording.step");
    payload.set("data", data);
    int started = service.handle(payload);

    org.assertj.core.api.Assertions.assertThat(started).isZero();
    verify(recordings).accept(deviceId, owner, "browser.recording.step", data);
    verify(workflows, never()).findByUserIdAndStatus(any(), any(WorkflowStatus.class));
    verify(engine, never()).start(any(), org.mockito.ArgumentMatchers.anyInt(), any(), any());
  }
}
