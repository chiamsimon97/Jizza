package com.jizza.engine.workflow.nodes;

import com.jizza.engine.api.device.DeviceCommandDto;
import com.jizza.engine.api.device.DeviceCommandListener;
import com.jizza.engine.workflow.engine.SagaWorkflowEngine;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Wakes the run whose Device action step waits on a command once that command finishes. It runs
 * off the device's connection thread, and a wake that comes while the run is still saving is
 * picked up by the recovery sweep.
 */
@Component
public class DeviceRunWaker implements DeviceCommandListener, AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(DeviceRunWaker.class);

  /** The node name live-view pictures are filed under. */
  public static final String LIVE_VIEW = "live-view";

  private final ObjectProvider<SagaWorkflowEngine> engine;
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

  public DeviceRunWaker(ObjectProvider<SagaWorkflowEngine> engine) {
    this.engine = engine;
  }

  @Override
  public void onFinished(DeviceCommandDto command) {
    // A picture taken for the person watching the run (live view) is no step of it.
    if (command.runId() == null || LIVE_VIEW.equals(command.nodeId())) return;
    executor.execute(() -> {
      try {
        engine.getObject().wake(command.runId());
      } catch (RuntimeException e) {
        log.warn("Could not wake run {} after device command {}", command.runId(), command.id(), e);
      }
    });
  }

  @Override
  public void close() {
    executor.shutdown();
  }
}
