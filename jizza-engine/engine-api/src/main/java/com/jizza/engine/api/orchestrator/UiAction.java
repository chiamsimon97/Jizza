package com.jizza.engine.api.orchestrator;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Something Jizza asks the user's screen to do as part of a reply: open a page, open a form with
 * fields filled in, put a question to the user, or change the theme.
 *
 * <p>Actions only steer the user's own view of Jizza. They never change data on their own; a form
 * Jizza fills in still waits for the user to save it.
 *
 * @param type    one of {@code navigate}, {@code open_form}, {@code ask_user}, {@code set_theme}
 * @param payload the action's arguments, shaped per type
 */
public record UiAction(String type, JsonNode payload) {

  public static final String NAVIGATE = "navigate";
  public static final String OPEN_FORM = "open_form";
  public static final String ASK_USER = "ask_user";
  public static final String SET_THEME = "set_theme";
}
