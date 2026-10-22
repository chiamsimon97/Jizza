package com.jizza.engine.orchestrator.chat;

import com.jizza.engine.agents.config.AgentProperties;
import com.jizza.engine.api.enums.ModelTier;
import com.jizza.engine.api.llm.CostAccountant;
import com.jizza.engine.api.llm.LlmClient;
import com.jizza.engine.api.llm.LlmResponse;
import com.jizza.engine.api.llm.Msg;
import com.jizza.engine.persistence.entity.AgentEntity;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Which model Jizza's own turn runs on (R49: a cheap model for routing, a stronger one for hard
 * work).
 *
 * <p>Most of what reaches Jizza is a greeting, a quick question, or something to pass to one
 * specialist and relay back. Those run on the routing model. Anything that needs Jizza itself to
 * plan, reason or decide runs on the standard model. A short call on the routing model makes the
 * choice; specialists keep their own tier regardless, so the hard part of a hand-off is never done
 * on the cheap model.
 *
 * <p>Every doubt goes to the standard model: routing switched off, a Jizza set to think harder, the
 * workflow planner, a long message, a check that fails, or an answer that is not plainly "simple".
 */
@Component
public class TurnTierSelector {

  private static final Logger log = LoggerFactory.getLogger(TurnTierSelector.class);

  /** How much of the conversation the check sees, so a "yes" is read against what it answers. */
  private static final int HISTORY_TURNS = 2;
  private static final int MAX_LINE_CHARS = 400;

  static final String TRIAGE_PROMPT = """
      You choose the model for the next message to Jizza, a personal assistant that hands work to
      specialists for health, finance and work. Answer with one word, simple or complex.
      simple: a greeting, thanks or small talk; a short factual question; a question about what
      Jizza can do; or a request that only needs passing to one specialist and relaying the answer.
      complex: anything that needs planning, several steps or several specialists, comparing
      options, writing something substantial, a money or health judgement, or changing settings,
      workflows or agents. A short reply that agrees to such a thing is complex too.
      If unsure, answer complex.""";

  private final LlmClient llm;
  private final CostAccountant costs;
  private final AgentProperties props;

  public TurnTierSelector(LlmClient llm, CostAccountant costs, AgentProperties props) {
    this.llm = llm;
    this.costs = costs;
    this.props = props;
  }

  public ModelTier tierFor(UUID userId, AgentEntity jizza, String message, String screen,
                           List<Msg> history) {
    if (!props.getRouting().isEnabled()) return ModelTier.STANDARD;
    // A Jizza set to think harder runs deep whatever is asked for; no point checking.
    if ("deep".equalsIgnoreCase(jizza.getModelTier())) return ModelTier.STANDARD;
    if (JizzaOrchestrator.WORKFLOW_PLANNER.equals(screen)) return ModelTier.STANDARD;
    if (message == null || message.isBlank()
        || message.length() > props.getRouting().getMaxSimpleChars()) {
      return ModelTier.STANDARD;
    }

    String model = llm.modelFor(ModelTier.ROUTING);
    try {
      LlmResponse response = llm.complete(model,
          List.of(Msg.system(TRIAGE_PROMPT), Msg.user(transcript(message, history))), List.of());
      costs.record(userId, model, response, "jizza:routing");
      String verdict = response.text() == null ? ""
          : response.text().strip().toLowerCase(Locale.ROOT);
      return verdict.startsWith("simple") ? ModelTier.ROUTING : ModelTier.STANDARD;
    } catch (RuntimeException e) {
      log.debug("Routing check failed, so this turn uses the standard model", e);
      return ModelTier.STANDARD;
    }
  }

  private static String transcript(String message, List<Msg> history) {
    StringBuilder text = new StringBuilder();
    List<Msg> said = history == null ? List.of() : history.stream()
        .filter(m -> (m.role() == Msg.Role.USER || m.role() == Msg.Role.ASSISTANT)
            && m.text() != null && !m.text().isBlank())
        .toList();
    if (!said.isEmpty()) {
      text.append("Earlier:\n");
      for (Msg m : said.subList(Math.max(0, said.size() - HISTORY_TURNS), said.size())) {
        text.append(m.role() == Msg.Role.USER ? "User: " : "Jizza: ").append(clip(m.text()))
            .append('\n');
      }
      text.append('\n');
    }
    return text.append("Next message:\n").append(message).toString();
  }

  private static String clip(String s) {
    return s.length() <= MAX_LINE_CHARS ? s : s.substring(0, MAX_LINE_CHARS) + "…";
  }
}
