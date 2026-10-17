package com.jizza.engine.api.activity;

/**
 * Where a running job's activity goes: the Activity panel's timeline, live over the user's socket
 * and kept for reopening later. One sink per job.
 */
public interface ActivitySink {

  void emit(ActivityEvent event);

  /** Set when the user pressed Stop; the agent loop checks it between steps. */
  boolean cancelled();

  /**
   * What started the job: {@code chat} when the user typed a message, otherwise {@code
   * continuation}, {@code signal}, ... Tools that may only act on the user's own words (saving a
   * standing instruction) check it.
   */
  default String origin() { return null; }

  /** The conversation the job belongs to, if any. */
  default java.util.UUID conversationId() { return null; }
}
