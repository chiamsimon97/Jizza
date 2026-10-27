package com.jizza.engine.chat.steps;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.chat.ChatScope;
import com.jizza.engine.api.orchestrator.UiAction;
import com.jizza.engine.api.orchestrator.UserSteps;
import com.jizza.engine.api.tool.JsonSchemas;
import com.jizza.engine.api.tool.ToolCall;
import com.jizza.engine.api.tool.ToolProvider;
import com.jizza.engine.api.tool.ToolResult;
import com.jizza.engine.api.tool.ToolSpec;
import com.jizza.engine.api.util.Json;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@code app_show_steps}: puts steps only the user can take on the reply as a card with buttons,
 * such as "open this page and log in", then "done, continue".
 *
 * <p>Offered only to the agent answering the user in this turn (Jizza in the main chat, the lead in
 * a thread), since only it speaks to the user. Nothing is changed: the card opens links in the
 * user's browser and sends the user's own reply.
 */
@Component
public class StepsToolProvider implements ToolProvider {

  public static final String SHOW_STEPS = "app_show_steps";

  static final int MAX_LABEL = 80;
  static final int MAX_DETAIL = 300;

  private final ToolSpec spec = new ToolSpec(SHOW_STEPS,
      "Show the user steps only they can take as a card with buttons, instead of writing them "
          + "out with raw links: e.g. 1) open a sign-in page (a link button), 2) come back and "
          + "tap \"Done, continue\" (a reply button). Use it whenever the user has to do "
          + "something outside this chat before you can carry on. Sign-in links a tool returns "
          + "are shown as a button for you already. After calling it, say in a sentence what the "
          + "user needs to do, without pasting the links, and end your turn.",
      JsonSchemas.object(new LinkedHashMap<>(Map.of(
          "title", JsonSchemas.string("A short heading, e.g. \"Connect your Zerodha account\""),
          "intro", JsonSchemas.string("Optional: one sentence above the steps"),
          "steps", stepsSchema())),
          List.of("title", "steps")));

  @Override
  public List<ToolSpec> toolsFor(UUID agentId) {
    return ChatScope.isResponder(agentId) ? List.of(spec) : List.of();
  }

  @Override
  public boolean supports(UUID agentId, String toolName) {
    return SHOW_STEPS.equals(toolName) && ChatScope.isResponder(agentId);
  }

  @Override
  public ToolResult invoke(UUID agentId, ToolCall call) {
    if (!ChatScope.isResponder(agentId)) {
      return ToolResult.error("Only the agent answering the user can show them steps.");
    }
    JsonNode args = call.args() == null ? Json.obj() : call.args();
    String title = clip(args.path("title").asText(""), MAX_LABEL);
    if (title.isBlank()) return ToolResult.error("'title' must not be empty");
    ObjectNode payload = Json.obj().put("title", title).put("source", "agent");
    String intro = clip(args.path("intro").asText(""), MAX_DETAIL);
    if (!intro.isBlank()) payload.put("intro", intro);
    ArrayNode steps = payload.putArray("steps");
    for (JsonNode in : args.path("steps")) {
      if (steps.size() == UserSteps.MAX_STEPS) break;
      String label = clip(in.path("label").asText(""), MAX_LABEL);
      if (label.isBlank()) continue;
      ObjectNode step = steps.addObject().put("label", label);
      String detail = clip(in.path("detail").asText(""), MAX_DETAIL);
      if (!detail.isBlank()) step.put("detail", detail);
      String url = in.path("url").asText("").strip();
      String reply = clip(in.path("reply").asText(""), MAX_DETAIL);
      String buttonLabel = clip(in.path("button_label").asText(""), 40);
      if (!url.isEmpty()) {
        if (!UserSteps.safeUrl(url)) {
          return ToolResult.error("Step '" + label + "' has a link that is not an http(s) address.");
        }
        step.putObject("button").put("kind", "link")
            .put("label", buttonLabel.isBlank() ? "Open" : buttonLabel).put("url", url);
      } else if (!reply.isBlank()) {
        step.putObject("button").put("kind", "reply")
            .put("label", buttonLabel.isBlank() ? "Done, continue" : buttonLabel).put("text", reply);
      }
    }
    if (steps.isEmpty()) return ToolResult.error("Give at least one step with a label.");
    ChatScope.record(new UiAction(UiAction.SHOW_STEPS, payload));
    return ToolResult.ok(Json.obj().put("done", true).put("note", "The steps are on the user's "
        + "screen with their buttons. Say in a sentence what they need to do, without repeating "
        + "the links, and end your turn."));
  }

  private static ObjectNode stepsSchema() {
    ObjectNode item = JsonSchemas.object(new LinkedHashMap<>(Map.of(
        "label", JsonSchemas.string("What to do, a few words, e.g. \"Log in to Kite\""),
        "detail", JsonSchemas.string("Optional: one sentence of help"),
        "url", JsonSchemas.string("Optional: an https link the step's button opens in a new tab"),
        "reply", JsonSchemas.string("Optional, instead of url: the message the button sends for "
            + "the user, e.g. \"I've logged in. Please continue.\""),
        "button_label", JsonSchemas.string("Optional: the button's text, a few words"))),
        List.of("label"));
    ObjectNode node = Json.obj();
    node.put("type", "array");
    node.put("description", "The steps in order, at most " + UserSteps.MAX_STEPS);
    node.set("items", item);
    return node;
  }

  private static String clip(String s, int max) {
    String t = s == null ? "" : s.strip();
    return t.length() > max ? t.substring(0, max) : t;
  }
}
