package com.jizza.engine.api.activity;

/** The event types a job's timeline is made of. */
public final class ActivityTypes {

  private ActivityTypes() {}

  public static final String JOB_STARTED = "job.started";
  public static final String JOB_COMPLETED = "job.completed";
  public static final String JOB_FAILED = "job.failed";
  public static final String JOB_CANCELLED = "job.cancelled";

  /** A slice of Jizza's reply as it is written. Never persisted; never from a specialist. */
  public static final String DELTA = "delta";
  /** The text streamed in that segment came before tool calls, so it is narration, not the answer. */
  public static final String SEGMENT_PREAMBLE = "segment.preamble";

  /** An agent started working (Jizza on the turn, or a specialist on a delegated task). */
  public static final String AGENT_STARTED = "agent.started";
  /** One agent speaking to another: Jizza's task to a specialist, or the specialist's answer. */
  public static final String AGENT_MESSAGE = "agent.message";
  /** A tool an agent called, with its state. */
  public static final String TOOL_CALL = "tool.call";
  /** The HTTP request a connector tool made: service, method, path template, status, duration. */
  public static final String TOOL_API = "tool.api";
  /** The memory an agent was handed before it started. */
  public static final String MEMORY_USED = "memory.used";
  /** Something saved to memory during the job. */
  public static final String MEMORY_SAVED = "memory.saved";
  /** Data a tool returned that is worth showing, already summarised. */
  public static final String OUTPUT = "output";
  /** A step needs the user's approval before it can run. */
  public static final String APPROVAL_REQUIRED = "approval.required";
  /** A guardrail settled a call without the user: Jizza approved it, or a never rule stopped it. */
  public static final String GUARDRAIL_DECIDED = "guardrail.decided";
  /** A tool handed back a link the user must open to sign in; the reply shows it as a button. */
  public static final String SIGN_IN_LINK = "sign_in.link";
  /** Something Jizza asked the user's screen to do. */
  public static final String UI_ACTION = "ui.action";
  /** Web pages an agent searched, read or cited for its reply: {@code sources} of url and title. */
  public static final String WEB_SOURCES = "web.sources";

  /** The conversation was given a title after its first exchange. */
  public static final String CONVERSATION_TITLED = "conversation.titled";
}
