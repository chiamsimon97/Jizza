package com.jizza.engine.api.activity;

/** Thrown inside a job when the user stopped it. Unwinds the agent loop without being an error. */
public class JobCancelledException extends RuntimeException {
  public JobCancelledException() {
    super("Stopped by the user");
  }
}
