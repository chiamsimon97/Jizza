package com.jizza.platform.usecase.device;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.enums.ModelTier;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.llm.CostAccountant;
import com.jizza.engine.api.llm.LlmClient;
import com.jizza.engine.api.llm.LlmResponse;
import com.jizza.engine.api.llm.Msg;
import com.jizza.engine.api.util.Json;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The phone app's Ask tab: a short answer about the person's own Jizza from facts the server
 * gathered, on the cheap model. It only reads. When the person asks to do something it may offer
 * one command, which the app shows as a card to confirm with the fingerprint and then sends to the
 * phone API's own signed route, so nothing runs from here.
 */
@Service
public class PhoneAskUseCase {

  public static final int MAX_QUESTION = 500;

  private static final String PROMPT = """
      You are Jizza on the person's phone. Answer their question about their own Jizza in one to
      three short sentences, using only the facts given (JSON). Say plainly when the facts don't
      cover it, and suggest opening Jizza on the web. Times in the facts are UTC; the person's
      time zone is given. Spend is in Jizza cost units.

      You can't do anything yourself. When the person asks to run a workflow, cancel a run, pause
      everything or resume everything, offer exactly one command for them to confirm:
        RUN_WORKFLOW with workflowId, only for a workflow listed under "workflows" with
          "runsWithoutInput": true;
        CANCEL_RUN with runId, only for a run listed under "activeRuns";
        PAUSE_ALL; RESUME_ALL.
      Otherwise command is null. Never offer anything else, and never claim it is done.

      Reply with JSON only, no other text:
      {"answer": "...", "command": null | {"type": "...", "workflowId": "...", "runId": "..."}}
      """;

  /** One command the app may offer to confirm. */
  public enum CommandType { RUN_WORKFLOW, CANCEL_RUN, PAUSE_ALL, RESUME_ALL }

  public record Command(CommandType type, UUID workflowId, UUID runId, String label) {}

  public record Answer(String answer, Command command) {}

  private final LlmClient llm;
  private final CostAccountant costs;

  public PhoneAskUseCase(LlmClient llm, CostAccountant costs) {
    this.llm = llm;
    this.costs = costs;
  }

  /**
   * @param facts       what the model may read
   * @param runnable    workflows that may be offered to run, id to name
   * @param activeRuns  runs that may be offered to cancel, id to workflow name
   */
  public Answer ask(UUID userId, String question, JsonNode facts, Map<UUID, String> runnable,
                    Map<UUID, String> activeRuns) {
    String q = question == null ? "" : question.strip();
    if (q.isEmpty()) throw new ValidationException("Ask something first");
    if (q.length() > MAX_QUESTION) {
      throw new ValidationException("Questions are limited to " + MAX_QUESTION + " characters");
    }
    if (!costs.withinBudget(userId)) {
      throw new ValidationException("Your spending limit for this period has been reached");
    }
    String model = llm.modelFor(ModelTier.ROUTING);
    LlmResponse response = llm.complete(model,
        List.of(Msg.system(PROMPT), Msg.user("Facts:\n" + Json.write(facts) + "\n\nQuestion: " + q)),
        List.of());
    costs.record(userId, model, response, "phone-ask");
    return read(response.text(), runnable, activeRuns);
  }

  /** Reads the model's reply, keeping only a command the facts allow. */
  static Answer read(String text, Map<UUID, String> runnable, Map<UUID, String> activeRuns) {
    JsonNode reply = parse(text);
    if (reply == null) {
      String plain = text == null ? "" : text.strip();
      return new Answer(plain.isEmpty() ? "I couldn't answer that just now. Try again." : clip(plain), null);
    }
    String answer = reply.path("answer").asText("").strip();
    Command command = command(reply.path("command"), runnable, activeRuns);
    if (answer.isEmpty()) answer = command == null ? "I couldn't answer that just now. Try again." : command.label();
    return new Answer(clip(answer), command);
  }

  private static Command command(JsonNode node, Map<UUID, String> runnable,
                                 Map<UUID, String> activeRuns) {
    if (node == null || !node.isObject()) return null;
    CommandType type;
    try {
      type = CommandType.valueOf(node.path("type").asText(""));
    } catch (IllegalArgumentException e) {
      return null;
    }
    return switch (type) {
      case RUN_WORKFLOW -> {
        UUID id = uuid(node.path("workflowId").asText(null));
        yield id != null && runnable.containsKey(id)
            ? new Command(type, id, null, "Run " + runnable.get(id)) : null;
      }
      case CANCEL_RUN -> {
        UUID id = uuid(node.path("runId").asText(null));
        yield id != null && activeRuns.containsKey(id)
            ? new Command(type, null, id, "Cancel the " + activeRuns.get(id) + " run") : null;
      }
      case PAUSE_ALL -> new Command(type, null, null, "Pause all agents and workflows");
      case RESUME_ALL -> new Command(type, null, null, "Resume all agents and workflows");
    };
  }

  private static JsonNode parse(String text) {
    if (text == null) return null;
    int start = text.indexOf('{');
    int end = text.lastIndexOf('}');
    if (start < 0 || end <= start) return null;
    try {
      JsonNode node = Json.mapper().readTree(text.substring(start, end + 1));
      return node != null && node.isObject() ? node : null;
    } catch (Exception e) {
      return null;
    }
  }

  private static UUID uuid(String value) {
    if (value == null) return null;
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private static String clip(String text) {
    return text.length() > 1200 ? text.substring(0, 1200) + "…" : text;
  }
}
