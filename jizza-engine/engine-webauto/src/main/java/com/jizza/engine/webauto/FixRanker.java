package com.jizza.engine.webauto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.jizza.engine.api.enums.ModelTier;
import com.jizza.engine.api.llm.CostAccountant;
import com.jizza.engine.api.llm.LlmClient;
import com.jizza.engine.api.llm.LlmResponse;
import com.jizza.engine.api.llm.Msg;
import com.jizza.engine.api.util.Json;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Optionally asks the cheap model which candidate most likely is the element a step lost, by
 * comparing labels and nearby text. It only changes the order the person sees; the person still
 * chooses, and nothing is applied. Off unless {@code jizza.webauto.ai-fix-ranking} is true, and any
 * answer it cannot read leaves the browser's own order.
 */
@Component
class FixRanker {

  private static final Logger log = LoggerFactory.getLogger(FixRanker.class);
  private static final int MAX_TEXT = 120;
  private static final String PROMPT = """
      A web automation step could no longer find the page element it was built for. The browser
      found some other elements that look similar. Order the candidates from most to least likely
      to be the element the step meant. Answer with the candidate numbers only, best first,
      separated by commas, for example: 2,0,1""";

  private final boolean enabled;
  private final ObjectProvider<LlmClient> llm;
  private final ObjectProvider<CostAccountant> costs;

  FixRanker(@Value("${jizza.webauto.ai-fix-ranking:false}") boolean enabled,
            ObjectProvider<LlmClient> llm, ObjectProvider<CostAccountant> costs) {
    this.enabled = enabled;
    this.llm = llm;
    this.costs = costs;
  }

  boolean enabled() {
    return enabled;
  }

  /** The candidates in the model's order, or as given when ranking is off or fails. */
  ArrayNode rank(UUID userId, JsonNode lost, ArrayNode candidates) {
    LlmClient client = llm.getIfAvailable();
    if (!enabled || client == null || candidates.size() < 2) return candidates;
    try {
      String model = client.modelFor(ModelTier.ROUTING);
      LlmResponse response = client.complete(model,
          List.of(Msg.system(PROMPT), Msg.user(describe(lost, candidates))), List.of());
      CostAccountant accountant = costs.getIfAvailable();
      if (accountant != null) accountant.record(userId, model, response, "webauto:fix-ranking");
      return reorder(candidates, response.text());
    } catch (RuntimeException e) {
      log.debug("Ranking fix candidates failed, so the browser's order is kept", e);
      return candidates;
    }
  }

  static ArrayNode reorder(ArrayNode candidates, String answer) {
    if (answer == null) return candidates;
    Set<Integer> order = new LinkedHashSet<>();
    for (String part : answer.strip().split("[,\\s]+")) {
      try {
        int index = Integer.parseInt(part.strip());
        if (index < 0 || index >= candidates.size()) return candidates;
        order.add(index);
      } catch (NumberFormatException e) {
        return candidates;
      }
    }
    if (order.isEmpty()) return candidates;
    for (int i = 0; i < candidates.size(); i++) order.add(i);
    ArrayNode out = Json.mapper().createArrayNode();
    order.forEach(i -> out.add(candidates.get(i)));
    return out;
  }

  /** Labels and short visible text only: never typed values or page HTML. */
  private static String describe(JsonNode lost, ArrayNode candidates) {
    StringBuilder text = new StringBuilder("The step looked for: ")
        .append(summary(lost)).append("\n\nCandidates:\n");
    List<String> lines = new ArrayList<>();
    for (int i = 0; i < candidates.size(); i++) {
      JsonNode c = candidates.get(i);
      lines.add(i + ". text \"" + clip(c.path("text").asText("")) + "\", "
          + summary(c.path("element")));
    }
    return text.append(String.join("\n", lines)).toString();
  }

  private static String summary(JsonNode element) {
    JsonNode print = element.path("fingerprint");
    return "label \"" + clip(element.path("label").asText("")) + "\", tag \""
        + clip(print.path("tag").asText("")) + "\", text \"" + clip(print.path("text").asText(""))
        + "\", near \"" + clip(print.path("nearText").asText("")) + "\"";
  }

  private static String clip(String s) {
    String flat = s.replaceAll("\\s+", " ").strip();
    return flat.length() <= MAX_TEXT ? flat : flat.substring(0, MAX_TEXT) + "…";
  }
}
