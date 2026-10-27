package com.jizza.engine.api.device;

/**
 * Told, after the change is committed, when a command finishes in any way. The workflow engine
 * uses it to wake the step that waits on the command.
 */
public interface DeviceCommandListener {

  void onFinished(DeviceCommandDto command);
}
