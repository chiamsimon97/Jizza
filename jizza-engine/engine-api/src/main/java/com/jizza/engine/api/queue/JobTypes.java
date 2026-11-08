package com.jizza.engine.api.queue;

/** Job-type constants shared between the modules that enqueue and the modules that consume. */
public final class JobTypes {

  private JobTypes() {}

  public static final String SCHEDULED_TASK_DUE = "scheduled_task_due";
  public static final String WORKFLOW_ADVANCE = "workflow_advance";
  public static final String NOTIFICATION_ESCALATION = "notification_escalation";
  public static final String INBOUND_EVENT = "inbound_event";
  /** Something happened on a paired device that may start workflows. */
  public static final String DEVICE_EVENT = "device_event";
  /** Someone submitted a form; its workflows start from this job. */
  public static final String FORM_SUBMITTED = "form_submitted";
}
