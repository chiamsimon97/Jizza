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
}
