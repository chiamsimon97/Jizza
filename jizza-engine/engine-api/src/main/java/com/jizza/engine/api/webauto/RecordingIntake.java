package com.jizza.engine.api.webauto;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

/**
 * Where {@code browser.recording.*} device events go instead of the workflow triggers. A recording
 * is a person doing a task in their own browser, so its events must never start a workflow.
 */
public interface RecordingIntake {

  /**
   * @param deviceId the device that sent the event
   * @param ownerId  the person who owns that device
   * @param name     {@code browser.recording.step}, {@code .edit} or {@code .stopped}
   */
  void accept(UUID deviceId, UUID ownerId, String name, JsonNode data);
}
