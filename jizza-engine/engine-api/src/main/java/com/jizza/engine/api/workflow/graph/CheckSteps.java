package com.jizza.engine.api.workflow.graph;

import com.jizza.engine.api.enums.NodeType;
import java.util.Map;

/**
 * Device steps that answer a yes/no question, such as "is this element on the page?". Besides
 * their usual way out (taken on yes) they may have a second one marked {@code "false"}, taken on
 * no. A check with nothing wired to "no" carries on along its usual way either way, as it always
 * has.
 */
public final class CheckSteps {
  /** The No way out of a check step. */
  public static final String NO = "false";

  /** Each check action and the yes/no field of its result. */
  private static final Map<String, String> ANSWERS = Map.of(
      "browser.exists", "exists",
      "browser.getCheckbox", "checked",
      "browser.getRadio", "checked");

  private CheckSteps() {}

  /** The result field holding the answer, or null when {@code node} isn't a check step. */
  public static String answerField(WorkflowNode node) {
    if (node == null || node.type() != NodeType.DEVICE_ACTION || node.config() == null) return null;
    return ANSWERS.get(node.config().path("action").asText(""));
  }

  public static boolean isCheck(WorkflowNode node) { return answerField(node) != null; }
}
