package com.jizza.engine.workflow.engine;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Locale;
import java.util.Set;

/**
 * The ready-made answers to "a step could not complete, how would you like to proceed?". Each acts
 * on the run by itself; any other answer is taken as a note for trying the step again.
 *
 * <p>Answers arrive as text (from the inbox, the phone app or the run view), so each choice is
 * recognised by its exact wording, ignoring case and a trailing full stop. Only these phrases
 * count, so a written answer that merely mentions stopping is never read as "stop".
 */
enum FailedStepChoice {
  RETRY("Try again", Set.of("try again", "retry")),
  SKIP("Skip this step", Set.of("skip this step", "skip it this time", "skip")),
  // "Stop and wait for me" was the inbox's old wording; people who picked it wanted the run stopped.
  STOP("Stop the run", Set.of("stop the run", "stop", "stop run", "stop and wait for me",
      "cancel the run", "cancel"));

  private final String answer;
  private final Set<String> phrases;

  FailedStepChoice(String answer, Set<String> phrases) {
    this.answer = answer;
    this.phrases = phrases;
  }

  /** The wording the inbox shows and sends for this choice. */
  String answer() {
    return answer;
  }

  /**
   * What happens when nobody answers before the question expires: the step's {@code ifNoAnswer}
   * setting ({@code stop}, {@code skip} or {@code retry}), and otherwise the run is stopped, so a
   * run never sits waiting on a question nobody can answer any more.
   */
  static FailedStepChoice ifNoAnswer(JsonNode config) {
    String setting = config == null ? "" : config.path("ifNoAnswer").asText("");
    return switch (setting.trim().toLowerCase(Locale.ROOT)) {
      case "skip" -> SKIP;
      case "retry" -> RETRY;
      default -> STOP;
    };
  }

  /** The choice an answer names, or null for a written answer. */
  static FailedStepChoice of(String text) {
    if (text == null) return null;
    String plain = text.trim().toLowerCase(Locale.ROOT);
    while (plain.endsWith(".") || plain.endsWith("!")) plain = plain.substring(0, plain.length() - 1).trim();
    for (FailedStepChoice choice : values()) {
      if (choice.phrases.contains(plain)) return choice;
    }
    return null;
  }
}
