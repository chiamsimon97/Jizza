package com.jizza.engine.orchestrator.library;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.enums.ModelTier;
import com.jizza.engine.api.llm.CostAccountant;
import com.jizza.engine.api.llm.LlmClient;
import com.jizza.engine.api.llm.LlmResponse;
import com.jizza.engine.api.llm.Msg;
import com.jizza.engine.api.util.Json;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One call to the cheap model that reads a chat message against the saved workflows it may be
 * about: is the person asking to run one of them now, which one, and which of its inputs the
 * message already gives. Nothing else is sent: no device tools, no conversation history, no memory,
 * so it stays a few thousand tokens however many browsers are paired.
 *
 * <p>The model only reads. What it pulls out is checked against each input's rules afterwards, and
 * the person sees all of it on the start card before anything runs.
 */
class RunRequestReader {

  private static final Logger log = LoggerFactory.getLogger(RunRequestReader.class);

  static final String PROMPT = """
      You read one chat message and decide whether the person is asking to run one of their saved
      workflows now. Answer with a single JSON object and nothing else:
      {"run": true or false, "workflow": <number of the workflow>, "values": {"<input name>": <value>}}

      - "run" is true only when the message asks for the job to be done now, such as "book a
        ticket from A to B" or "run the expenses report". It is false for questions about a
        workflow, for building, changing or scheduling one, and for anything else.
      - Pick the one workflow that does what is asked. When none of them does, "run" is false.
      - Put in "values" only what the message says or plainly means. Never guess or invent a
        value; leave out every input the message does not give.
      - Dates as YYYY-MM-DD, worked out from today's date below ("tomorrow", "20 Dec", "next
        Friday"). A date with no year is the next one to come.
      - For an input with choices, give the choice exactly as listed when the message clearly
        means one of them ("3A" meaning "AC 3 Tier (3A)").
      - A list of rows is an array of objects with the row's keys. "Me and my mother" with a
        name, age or gender column gives one row each, filled only with what the message says.
      - Numbers as numbers; yes/no as true/false.""";

  private final LlmClient llm;
  private final CostAccountant costs;

  RunRequestReader(LlmClient llm, CostAccountant costs) {
    this.llm = llm;
    this.costs = costs;
  }

  /** One workflow the message may be about, with the inputs it takes. */
  record Candidate(String name, String summary, List<RunInputs.Input> inputs) {}

  /**
   * What the model read.
   *
   * @param index  which candidate, from 0
   * @param values the input values it found, by input name
   */
  record Reading(int index, JsonNode values) {}

  /** The workflow the person asked to run and what they gave for it; null when they asked for none. */
  Reading read(UUID userId, String message, List<Candidate> candidates, ZoneId zone) {
    if (candidates.isEmpty()) return null;
    String model = llm.modelFor(ModelTier.ROUTING);
    LlmResponse response = llm.complete(model,
        List.of(Msg.system(PROMPT), Msg.user(describe(message, candidates, zone))), List.of());
    if (costs != null) costs.record(userId, model, response, "chat:run-reader");
    return parse(response.text(), candidates.size());
  }

  static String describe(String message, List<Candidate> candidates, ZoneId zone) {
    LocalDate today = LocalDate.now(zone);
    StringBuilder text = new StringBuilder("Today is ")
        .append(today.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH)).append(' ')
        .append(today).append(" (").append(zone.getId()).append(").\n\nThe person's saved workflows:\n");
    for (int i = 0; i < candidates.size(); i++) {
      Candidate c = candidates.get(i);
      text.append(i + 1).append(". ").append(RunInputs.clip(c.name(), 80));
      if (c.summary() != null && !c.summary().isBlank()) {
        text.append(" — ").append(RunInputs.clip(c.summary(), 200));
      }
      text.append('\n');
      if (c.inputs().isEmpty()) text.append("  (takes no inputs)\n");
      else text.append(RunInputs.describe(c.inputs()));
    }
    return text.append("\nThe message:\n").append(RunInputs.clip(message, 1500)).toString();
  }

  /** The model's answer read strictly: anything unexpected means "not a run request". */
  static Reading parse(String answer, int candidates) {
    if (answer == null) return null;
    int start = answer.indexOf('{');
    int end = answer.lastIndexOf('}');
    if (start < 0 || end <= start) return null;
    try {
      JsonNode node = Json.mapper().readTree(answer.substring(start, end + 1));
      if (!node.path("run").asBoolean(false)) return null;
      int number = node.path("workflow").asInt(0);
      if (number < 1 || number > candidates) return null;
      JsonNode values = node.path("values");
      return new Reading(number - 1, values.isObject() ? values : Json.obj());
    } catch (Exception e) {
      log.debug("The run reader's answer was not JSON", e);
      return null;
    }
  }
}
