package com.jizza.engine.chat.thread;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.chat.ChatScope;
import com.jizza.engine.api.llm.CostAccountant;
import com.jizza.engine.api.llm.LlmClient;
import com.jizza.engine.api.llm.LlmResponse;
import com.jizza.engine.api.llm.Msg;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.util.Redaction;
import com.jizza.engine.chat.config.ChatProperties;
import com.jizza.engine.persistence.entity.AgentEntity;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * In a main chat without threads, hands a message in a specialist's area to that specialist, so
 * it answers with its own tools and knowledge, exactly as when the user writes "@Kai". Without
 * this, Jizza answered money questions itself and could only note a goal in its memory, while Kai
 * can save it to the Portfolio, show the form in the chat and sync a broker.
 *
 * <p>Only the specialists named in {@code jizza.chat.specialists.keys} take messages this way
 * (Kai by default); everything else stays with Jizza. One small model call decides, with the last
 * few messages in front of it so a short follow-up ("I meant 1 lakh", "yes, set a reminder") stays
 * with the specialist the exchange is with. When the call fails or its answer does not hold up,
 * Jizza answers as before.
 */
@Component
public class SpecialistRouter {

  private static final Logger log = LoggerFactory.getLogger(SpecialistRouter.class);

  static final int RECENT_MESSAGES = 6;
  private static final int MAX_RECENT_CHARS = 600;

  static final String PROMPT = """
      Your name is Jizza Specialist Router. Decide who answers one message in a chat: Jizza,
      the user's general assistant, or one of the listed specialists.

      Pick a specialist when the message is about that specialist's area, including a short
      follow-up to an exchange about it (a correction, an answer to a question it asked, "yes, do
      that"). For example, saving towards something, a goal with an amount or date, a loan, an
      insurance policy, investments, a budget, a broker or a bank statement are the finance
      specialist's, and so is a reminder about one of those. A health reading (blood pressure,
      sugar, weight), a medicine or a dose taken, a lab report, a doctor's visit, an allergy, a
      blood group, an emergency contact, or how someone in the family is feeling are the health
      specialist's.%s

      Pick "jizza" for small talk, thanks, questions about the Jizza app itself, workflows and
      automations, anything outside the listed areas, a request that spans several areas, or
      anything you are unsure of.

      Reply with JSON only, no prose: {"answer": "<specialist key or jizza>", "reason": "a few words"}""";

  /** Added while Vita's Health tab is on: Vita then owns health cover and claims (Vita spec 4.3). */
  static final String HEALTH_COVER = """
       Health insurance cover, a cashless card and a hospital claim are the health specialist's.""";

  private final ThreadService threads;
  private final LlmClient llm;
  private final CostAccountant costs;
  private final ChatProperties props;
  private final String systemPrompt;

  public SpecialistRouter(ThreadService threads, LlmClient llm, CostAccountant costs,
                          ChatProperties props) {
    this(threads, llm, costs, props, false);
  }

  @org.springframework.beans.factory.annotation.Autowired
  public SpecialistRouter(ThreadService threads, LlmClient llm, CostAccountant costs,
                          ChatProperties props,
                          @org.springframework.beans.factory.annotation.Value("${jizza.health.enabled:false}") boolean healthOn) {
    this.threads = threads;
    this.llm = llm;
    this.costs = costs;
    this.props = props;
    this.systemPrompt = prompt(healthOn);
  }

  /** The router's instructions; health cover goes to Vita only while the Health tab is on. */
  static String prompt(boolean healthOn) {
    return PROMPT.formatted(healthOn ? " " + HEALTH_COVER.strip() : "");
  }

  /**
   * The specialist who should answer this message instead of Jizza, or empty when Jizza should.
   *
   * @param screen the page the message came from; the workflow screens always stay with Jizza
   */
  public Optional<AgentEntity> route(ChatScope.Scope scope, String screen, String message,
                                     List<Msg> history) {
    if (!applies(scope, screen) || message == null || message.isBlank()) return Optional.empty();
    List<AgentEntity> candidates = candidates(scope);
    if (candidates.isEmpty()) return Optional.empty();
    try {
      String model = llm.modelFor(props.getSpecialists().getTier());
      LlmResponse response = llm.complete(model,
          List.of(Msg.system(systemPrompt), Msg.user(prompt(candidates, message, history))), List.of());
      costs.record(scope.userId(), response.model() == null ? model : response.model(),
          response.inputTokens(), response.outputTokens(), "chat:specialist-route");
      return pick(response.text(), candidates);
    } catch (RuntimeException e) {
      log.warn("Could not route a message in conversation {}", scope.conversationId(), e);
      return Optional.empty();
    }
  }

  boolean applies(ChatScope.Scope scope, String screen) {
    if (scope == null || scope.thread() || scope.threadsOn()) return false;
    if (!props.getSpecialists().isEnabled() || props.getSpecialists().getKeys().isEmpty()) return false;
    // The workflow planner, Builder and step settings are Jizza's own work.
    if (screen != null && screen.toLowerCase(Locale.ROOT).startsWith("workflow")) return false;
    return costs.withinBudget(scope.userId());
  }

  private List<AgentEntity> candidates(ChatScope.Scope scope) {
    List<String> keys = props.getSpecialists().getKeys().stream()
        .map(k -> k.toLowerCase(Locale.ROOT).strip()).toList();
    return threads.leads(scope.userId()).stream()
        .filter(a -> !a.isOrchestrator() && a.getKey() != null
            && keys.contains(a.getKey().toLowerCase(Locale.ROOT)))
        .toList();
  }

  private static String prompt(List<AgentEntity> candidates, String message, List<Msg> history) {
    ObjectNode body = Json.obj();
    ArrayNode specialists = body.putArray("specialists");
    for (AgentEntity a : candidates) {
      ObjectNode node = specialists.addObject();
      node.put("key", a.getKey());
      node.put("name", a.getName());
      if (a.getDescription() != null) node.put("area", a.getDescription());
    }
    ArrayNode recent = body.putArray("recent_messages");
    List<Msg> said = history.stream()
        .filter(m -> (m.role() == Msg.Role.USER || m.role() == Msg.Role.ASSISTANT)
            && m.text() != null && !m.text().isBlank() && m.toolCalls().isEmpty())
        .toList();
    for (Msg m : said.subList(Math.max(0, said.size() - RECENT_MESSAGES), said.size())) {
      recent.addObject().put("from", m.role() == Msg.Role.USER ? "user" : "assistant")
          .put("text", clip(Redaction.scrub(m.text())));
    }
    body.put("message", Redaction.scrub(message));
    return Json.write(body);
  }

  /** The model's answer, checked against the candidates; anything else leaves it with Jizza. */
  static Optional<AgentEntity> pick(String raw, List<AgentEntity> candidates) {
    JsonNode node = json(raw);
    if (node == null) return Optional.empty();
    String answer = node.path("answer").asText("").strip();
    if (answer.isEmpty() || "jizza".equalsIgnoreCase(answer)) return Optional.empty();
    return candidates.stream()
        .filter(a -> answer.equalsIgnoreCase(a.getKey()) || answer.equalsIgnoreCase(a.getName()))
        .findFirst();
  }

  private static JsonNode json(String raw) {
    if (raw == null) return null;
    int start = raw.indexOf('{');
    int end = raw.lastIndexOf('}');
    if (start < 0 || end <= start) return null;
    try {
      return Json.read(raw.substring(start, end + 1));
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static String clip(String s) {
    return s.length() <= MAX_RECENT_CHARS ? s : s.substring(0, MAX_RECENT_CHARS) + "…";
  }
}
