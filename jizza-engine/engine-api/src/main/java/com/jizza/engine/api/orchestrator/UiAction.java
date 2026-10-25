package com.jizza.engine.api.orchestrator;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Something Jizza asks the user's screen to do as part of a reply: open a page, open a form with
 * fields filled in, put a question to the user, or change the theme.
 *
 * <p>Actions only steer the user's own view of Jizza. They never change data on their own; a form
 * Jizza fills in still waits for the user to save it.
 *
 * @param type    one of {@code navigate}, {@code open_form}, {@code ask_user}, {@code set_theme},
 *                {@code propose_workflow}, {@code propose_agent}, {@code configure_step},
 *                {@code reused_workflow}, {@code thread_opened}, {@code thread_handback}
 * @param payload the action's arguments, shaped per type
 */
public record UiAction(String type, JsonNode payload) {

  public static final String NAVIGATE = "navigate";
  public static final String OPEN_FORM = "open_form";
  public static final String ASK_USER = "ask_user";
  public static final String SET_THEME = "set_theme";
  /** A workflow plan for the user to approve; the client saves it only when they do. */
  public static final String PROPOSE_WORKFLOW = "propose_workflow";
  /** A custom agent, or a whole team, for the user to approve; created only when they do. */
  public static final String PROPOSE_AGENT = "propose_agent";
  /** Settings for the Builder step the user asked Jizza to configure; they still save them. */
  public static final String CONFIGURE_STEP = "configure_step";
  /** Jizza ran one of the user's saved workflows for this request instead of building a new one. */
  public static final String REUSED_WORKFLOW = "reused_workflow";
  /** Jizza moved a topic into a thread led by a specialist, or passed a message to one. */
  public static final String THREAD_OPENED = "thread_opened";
  /** A thread's lead passed a message that does not belong there back to the main chat. */
  public static final String THREAD_HANDBACK = "thread_handback";
}
