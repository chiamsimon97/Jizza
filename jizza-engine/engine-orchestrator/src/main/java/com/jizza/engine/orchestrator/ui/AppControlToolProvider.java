package com.jizza.engine.orchestrator.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.orchestrator.UiAction;
import com.jizza.engine.api.tool.JsonSchemas;
import com.jizza.engine.api.tool.ToolCall;
import com.jizza.engine.api.tool.ToolProvider;
import com.jizza.engine.api.tool.ToolResult;
import com.jizza.engine.api.tool.ToolSpec;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.persistence.entity.AgentEntity;
import com.jizza.engine.persistence.repository.AgentRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Lets Jizza drive the app the user is looking at: open a page, open a form with the fields filled
 * in, ask the user a question with choices, or switch the theme.
 *
 * <p>Offered to the orchestrator only, since it is the one agent the user is talking to. None of
 * these tools changes data: they are recorded as {@link UiAction}s on the reply and the client
 * carries them out. A filled-in form still waits for the user to save it, so this cannot be used to
 * get around the autonomy policy.
 */
@Component
public class AppControlToolProvider implements ToolProvider {

  public static final String NAVIGATE = "app_navigate";
  public static final String OPEN_FORM = "app_open_form";
  public static final String ASK_USER = "app_ask_user";
  public static final String SET_THEME = "app_set_theme";

  public static final Set<String> TOOL_NAMES = Set.of(NAVIGATE, OPEN_FORM, ASK_USER, SET_THEME);

  static final List<String> PAGES = List.of("chat", "conversations", "notifications", "agents", "memory",
      "tasks", "connections", "workflows", "procedures", "guardrails", "activity", "runs", "usage");
  static final List<String> FORMS = List.of("new_workflow", "new_task");
  static final List<String> THEMES = List.of("light", "dark", "sunset");

  private static final String FORM_FIELDS_HELP = """
      Values to fill in. For new_workflow: {"name": "...", "description": "...", "steps": [
      {"kind": "agent", "agent_key": "<specialist key>", "instruction": "what that agent does"},
      {"kind": "ask", "instruction": "what to ask the user at this point"}]} -- steps run in order.
      For new_task: {"title": "...", "agent_key": "<agent key>", "schedule": "once" or "recurring",
      "run_at": "<ISO-8601 date-time, for once>", "cron": "<cron expression, for recurring>",
      "timezone": "<IANA zone, e.g. Asia/Singapore>"}. Leave out anything you do not know.
      """;

  private final AgentRepository agents;
  private final UiActionRecorder recorder;
  private final List<ToolSpec> specs;

  public AppControlToolProvider(AgentRepository agents, UiActionRecorder recorder) {
    this.agents = agents;
    this.recorder = recorder;
    this.specs = List.of(
        new ToolSpec(NAVIGATE,
            "Open a page of the Jizza app on the user's screen. Use it whenever the user wants to "
                + "see or work in a part of the app, instead of telling them where to click.",
            JsonSchemas.object(new LinkedHashMap<>(Map.of(
                "page", enumOf("The page to open", PAGES),
                "reason", JsonSchemas.string("Why, in a few words, shown to the user"))),
                List.of("page"))),
        new ToolSpec(OPEN_FORM,
            "Open a form on the user's screen with the fields already filled in, so the user only "
                + "has to review and save. Use it when the user wants to create a workflow or a "
                + "task: fill in everything you can work out from the conversation.",
            JsonSchemas.object(new LinkedHashMap<>(Map.of(
                "form", enumOf("Which form to open", FORMS),
                "fields", JsonSchemas.objectSchema(FORM_FIELDS_HELP))),
                List.of("form"))),
        new ToolSpec(ASK_USER,
            "Stop and ask the user something only they can decide, with optional one-tap choices. "
                + "Use it only when you cannot reasonably proceed without their answer. After "
                + "calling it, end your turn and wait for their reply.",
            JsonSchemas.object(new LinkedHashMap<>(Map.of(
                "question", JsonSchemas.string("The question, one sentence"),
                "options", stringArray("Up to five short answers the user can tap"))),
                List.of("question"))),
        new ToolSpec(SET_THEME,
            "Switch the app's colour theme when the user asks for it.",
            JsonSchemas.object(new LinkedHashMap<>(Map.of(
                "theme", enumOf("The theme", THEMES))),
                List.of("theme"))));
  }

  @Override
  public List<ToolSpec> toolsFor(UUID agentId) {
    return isOrchestrator(agentId) ? specs : List.of();
  }

  @Override
  public boolean supports(UUID agentId, String toolName) {
    return TOOL_NAMES.contains(toolName) && isOrchestrator(agentId);
  }

  @Override
  public ToolResult invoke(UUID agentId, ToolCall call) {
    if (!recorder.active()) {
      return ToolResult.error("The user is not looking at the app right now, so there is no "
          + "screen to act on. Answer in text instead.");
    }
    JsonNode args = call.args() == null ? Json.obj() : call.args();
    return switch (call.name()) {
      case NAVIGATE -> navigate(args);
      case OPEN_FORM -> openForm(args);
      case ASK_USER -> askUser(args);
      case SET_THEME -> setTheme(args);
      default -> ToolResult.error("Unknown app tool " + call.name());
    };
  }

  private ToolResult navigate(JsonNode args) {
    String page = args.path("page").asText();
    if (!PAGES.contains(page)) return ToolResult.error("Unknown page '" + page + "'");
    ObjectNode payload = Json.obj().put("page", page);
    if (args.hasNonNull("reason")) payload.put("reason", args.path("reason").asText());
    recorder.record(new UiAction(UiAction.NAVIGATE, payload));
    return done("The " + page + " page is now open on the user's screen.");
  }

  private ToolResult openForm(JsonNode args) {
    String form = args.path("form").asText();
    if (!FORMS.contains(form)) return ToolResult.error("Unknown form '" + form + "'");
    ObjectNode payload = Json.obj().put("form", form);
    payload.set("fields", args.path("fields").isObject() ? args.path("fields") : Json.obj());
    recorder.record(new UiAction(UiAction.OPEN_FORM, payload));
    return done("The form is open with your values filled in. The user reviews it and saves; "
        + "nothing is created until they do. Tell them briefly what you filled in.");
  }

  private ToolResult askUser(JsonNode args) {
    String question = args.path("question").asText();
    if (question.isBlank()) return ToolResult.error("'question' must not be empty");
    ObjectNode payload = Json.obj().put("question", question);
    ArrayNode options = payload.putArray("options");
    for (JsonNode option : args.path("options")) {
      if (options.size() == 5) break;
      if (!option.asText().isBlank()) options.add(option.asText());
    }
    recorder.record(new UiAction(UiAction.ASK_USER, payload));
    return done("The question is on the user's screen. End your turn now and wait for the answer; "
        + "do not repeat the question in your reply.");
  }

  private ToolResult setTheme(JsonNode args) {
    String theme = args.path("theme").asText();
    if (!THEMES.contains(theme)) return ToolResult.error("Unknown theme '" + theme + "'");
    recorder.record(new UiAction(UiAction.SET_THEME, Json.obj().put("theme", theme)));
    return done("Switched to the " + theme + " theme.");
  }

  private static ToolResult done(String note) {
    return ToolResult.ok(Json.obj().put("done", true).put("note", note));
  }

  private static ObjectNode enumOf(String description, List<String> values) {
    ObjectNode node = JsonSchemas.string(description);
    ArrayNode allowed = node.putArray("enum");
    values.forEach(allowed::add);
    return node;
  }

  private static ObjectNode stringArray(String description) {
    ObjectNode node = Json.obj();
    node.put("type", "array");
    node.put("description", description);
    node.set("items", Json.obj().put("type", "string"));
    return node;
  }

  private boolean isOrchestrator(UUID agentId) {
    return agents.findById(agentId).map(AgentEntity::isOrchestrator).orElse(false);
  }
}
