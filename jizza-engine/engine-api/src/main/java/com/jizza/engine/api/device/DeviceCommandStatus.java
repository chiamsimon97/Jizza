package com.jizza.engine.api.device;

/** One command's life on its way to a device and back. */
public enum DeviceCommandStatus {
  /** Saved, waiting for the device to be reachable. */
  QUEUED,
  /** Sent; the device has not said it took it yet. */
  DISPATCHED,
  /** The device checked it and will run it. */
  ACCEPTED,
  /** The device reported progress. */
  RUNNING,
  SUCCEEDED,
  FAILED,
  /** The device refused it: its own limits, a missing OS permission, a bad signature. */
  REJECTED,
  /** Its deadline passed before it finished. */
  EXPIRED,
  CANCELLED;

  public boolean finished() {
    return this == SUCCEEDED || this == FAILED || this == REJECTED || this == EXPIRED
        || this == CANCELLED;
  }

  /** Handed to the device and not finished, so stopping it needs a cancel message. */
  public boolean onDevice() {
    return this == DISPATCHED || this == ACCEPTED || this == RUNNING;
  }
}
