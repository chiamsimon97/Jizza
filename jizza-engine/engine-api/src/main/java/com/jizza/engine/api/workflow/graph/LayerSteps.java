package com.jizza.engine.api.workflow.graph;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.enums.NodeType;
import java.util.ArrayList;
import java.util.List;

/**
 * Device layer steps ({@code browser.fillLayer}): one step fills a whole area of a page and then
 * clicks one of the area's buttons. Each button is a way out of the step, marked with the
 * button's id, and the run goes on along the way of the button that was clicked (the result's
 * {@code clicked}). A button with nothing wired to it ends that path.
 */
public final class LayerSteps {
  public static final String ACTION = "browser.fillLayer";
  /** The result field naming the button clicked, by id. */
  public static final String CLICKED = "clicked";

  private LayerSteps() {}

  public static boolean isLayer(WorkflowNode node) {
    return node != null && node.type() == NodeType.DEVICE_ACTION && node.config() != null
        && ACTION.equals(node.config().path("action").asText(""));
  }

  /** The ids of the layer's buttons, in order: its ways out. */
  public static List<String> buttonIds(WorkflowNode node) {
    List<String> ids = new ArrayList<>();
    if (!isLayer(node)) return ids;
    for (JsonNode button : node.config().path("inputs").path("buttons")) {
      String id = button.path("id").asText("").strip();
      if (!id.isEmpty() && !ids.contains(id)) ids.add(id);
    }
    return ids;
  }

  /**
   * The way out a layer takes when the person skipped it after it failed (so it clicked nothing):
   * the button it was set to click, by id or name (the one chosen on the card first), else the
   * first button that sends the form, else its first button. Null when it has no buttons.
   */
  public static String skippedButton(WorkflowNode node, JsonNode approved) {
    if (!isLayer(node)) return null;
    JsonNode buttons = node.config().path("inputs").path("buttons");
    for (String wanted : new String[] {
        approved == null ? "" : approved.path("button").asText(""),
        node.config().path("inputs").path("button").asText("")}) {
      String want = wanted.strip();
      if (want.isEmpty() || want.contains("{{")) continue;
      for (JsonNode b : buttons) {
        String id = b.path("id").asText("").strip();
        if (id.isEmpty()) continue;
        if (want.equals(id) || want.equalsIgnoreCase(b.path("name").asText("").strip())) return id;
      }
    }
    for (JsonNode b : buttons) {
      String id = b.path("id").asText("").strip();
      if (!id.isEmpty() && "submit".equals(b.path("kind").asText(""))) return id;
    }
    List<String> ids = buttonIds(node);
    return ids.isEmpty() ? null : ids.get(0);
  }
}
