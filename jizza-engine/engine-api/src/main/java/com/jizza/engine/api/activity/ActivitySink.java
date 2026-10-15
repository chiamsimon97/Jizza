package com.jizza.engine.api.activity;

/**
 * Where a running job's activity goes: the Activity panel's timeline, live over the user's socket
 * and kept for reopening later. One sink per job.
 */
public interface ActivitySink {

  void emit(ActivityEvent event);

  /** Set when the user pressed Stop; the agent loop checks it between steps. */
  boolean cancelled();
}
