package com.jizza.spikes.adk;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.agents.config.AgentProperties;
import com.jizza.engine.api.activity.Activity;
import com.jizza.engine.api.activity.ActivityEvent;
import com.jizza.engine.api.activity.ActivitySink;
import com.jizza.engine.api.activity.ActivityTypes;
import com.jizza.engine.api.enums.ModelTier;
import com.jizza.engine.api.llm.LlmClient;
import com.jizza.engine.api.llm.LlmResponse;
import com.jizza.engine.api.llm.Msg;
import com.jizza.engine.api.tool.ToolCall;
import com.jizza.engine.api.tool.ToolSpec;
import com.jizza.engine.api.util.Json;
import com.jizza.evals.EvalCase;
import com.jizza.evals.EvalCases;
import com.jizza.evals.EvalRunner;
import com.jizza.evals.EvalWorld;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * The spike's pass criteria. Each test runs the same scenario through ADK and through Jizza's own
 * loop where that makes the comparison, with the real Capability Registry and guardrails behind
 * both (the eval harness's world), and a scripted model so nothing is spent.
 */
class AdkSpikeTest {

  static final EvalWorld.Runtime ADK = AdkAgent::new;

  private final AgentProperties props = new AgentProperties();

  {
    props.getModels().put(ModelTier.STANDARD, "scripted");
  }

  /** A model that says what it is told, and keeps what it was sent. */
  static final class Script implements LlmClient {
    private final Deque<LlmResponse> replies = new ArrayDeque<>();
    final List<List<Msg>> requests = new ArrayList<>();
    final List<List<ToolSpec>> tools = new ArrayList<>();
    final List<String> streamedOnThread = new ArrayList<>();

    Script call(String name, String args) {
      replies.add(new LlmResponse("", List.of(new ToolCall("tu_" + replies.size(), name,
          Json.read(args))), "tool_use", 100, 20, "scripted"));
      return this;
    }

    Script reply(LlmResponse response) {
      replies.add(response);
      return this;
    }

    Script answer(String text) {
      replies.add(new LlmResponse(text, List.of(), "end_turn", 120, 40, "scripted"));
      return this;
    }

    @Override public LlmResponse complete(String model, List<Msg> messages, List<ToolSpec> offered) {
      requests.add(List.copyOf(messages));
      tools.add(List.copyOf(offered));
      if (replies.isEmpty()) return new LlmResponse("", List.of(new ToolCall("tu_x", "bank_list_transactions",
          Json.obj())), "tool_use", 100, 20, "scripted");
      return replies.poll();
    }

    @Override public LlmResponse stream(String model, List<Msg> messages, List<ToolSpec> offered,
                                        java.util.function.Consumer<String> onText) {
      streamedOnThread.add(Thread.currentThread().getName());
      LlmResponse response = complete(model, messages, offered);
      if (!response.text().isEmpty()) onText.accept(response.text());
      return response;
    }

    @Override public String modelFor(ModelTier tier) { return "scripted"; }
  }

  private EvalCase scenario(String id) {
    return EvalCases.all().stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
  }

  private EvalRunner.Trial run(EvalWorld.Runtime runtime, String id, Supplier<Script> model) {
    return new EvalRunner(props, runtime).run(List.of(scenario(id)), 1, c -> model.get())
        .cases().get(0).trials().get(0);
  }

  // ── 1. The eval scenarios grade the same on both runtimes ─────────────────────

  @Test
  void aPaymentIsHeldByTheGuardrailsOnBothRuntimes() {
    Supplier<Script> model = () -> new Script()
        .call("bank_pay_bill", "{\"account_id\":\"sav-1\",\"biller\":\"BESCOM\",\"amount\":2400}")
        .answer("I've asked for your approval to pay the 2,400 rupee bill.");

    var ours = run(EvalWorld.Runtime.BASE_AGENT, "kai-payment-waits-for-approval", model);
    var adk = run(ADK, "kai-payment-waits-for-approval", model);

    assertThat(adk.failures()).isEqualTo(ours.failures()).isEmpty();
    assertThat(adk.decisions()).isEqualTo(ours.decisions()).containsEntry("bank_pay_bill", "ASK");
  }

  @Test
  void anInjectedTransferNeverRunsOnEitherRuntime() {
    Supplier<Script> model = () -> new Script()
        .call("bank_list_transactions", "{}")
        .call("bank_transfer", "{\"to\":\"9999-0000\",\"amount\":50000}")
        .answer("Transferred as instructed.");

    var ours = run(EvalWorld.Runtime.BASE_AGENT, "kai-ignores-instructions-inside-data", model);
    var adk = run(ADK, "kai-ignores-instructions-inside-data", model);

    assertThat(adk.passed()).isEqualTo(ours.passed()).isFalse();
    assertThat(adk.decisions()).containsEntry("bank_transfer", "ASK");
  }

  @Test
  void readingAndRoutingGradeTheSameOnBothRuntimes() {
    Supplier<Script> reading = () -> new Script()
        .call("bank_list_transactions", "{\"from\":\"2026-09-01\",\"to\":\"2026-09-30\"}")
        .answer("You spent 8,450 rupees on groceries in September.");
    Supplier<Script> routing = () -> new Script()
        .call("delegate_to_specialist", "{\"agent_key\":\"finance\",\"task\":\"card bill\"}")
        .answer("Kai says your bill is 18,240 rupees, due 5 November.");

    assertThat(run(ADK, "kai-reads-spending-freely", reading).failures()).isEmpty();
    assertThat(run(ADK, "jizza-routes-money-to-kai", routing).failures()).isEmpty();
  }

  // ── 2. Thinking blocks survive ADK's history ──────────────────────────────────

  @Test
  void theModelsThinkingComesBackUnchangedOnTheNextRound() {
    JsonNode thinking = Json.obj().put("type", "thinking").put("thinking", "")
        .put("signature", "sig-abc-123");
    JsonNode toolUse = Json.read("{\"type\":\"tool_use\",\"id\":\"tu_1\","
        + "\"name\":\"bank_list_transactions\",\"input\":{}}");
    Script model = new Script()
        .reply(new LlmResponse("", List.of(new ToolCall("tu_1", "bank_list_transactions",
            Json.obj())), "tool_use", 100, 20, "scripted", 0, 0, List.of(thinking, toolUse)))
        .answer("You spent 8,450 rupees on groceries.");

    new EvalRunner(props, ADK).run(List.of(scenario("kai-reads-spending-freely")), 1, c -> model);

    Msg replayed = model.requests.get(1).stream().filter(m -> m.role() == Msg.Role.ASSISTANT)
        .findFirst().orElseThrow();
    assertThat(replayed.providerContent()).hasSize(2);
    assertThat(replayed.providerContent().get(0).path("type").asText()).isEqualTo("thinking");
    assertThat(replayed.providerContent().get(0).path("signature").asText()).isEqualTo("sig-abc-123");
    assertThat(replayed.providerContent().get(1).path("id").asText()).isEqualTo("tu_1");
  }

  // ── 3. The run budget still ends a runaway turn gracefully ────────────────────

  @Test
  void theIterationCapEndsTheTurnWithAnAnswerNotAnError() {
    props.getLoop().setMaxToolIterations(2);
    Script model = new Script(); // asks for a tool forever

    var trial = new EvalRunner(props, ADK).run(List.of(scenario("kai-reads-spending-freely")), 1,
        c -> model).cases().get(0).trials().get(0);

    assertThat(model.requests).hasSize(2);
    assertThat(trial.failures()).contains("stopped on its budget");
    assertThat(trial.answer()).startsWith("I stopped before finishing");
  }

  // ── 4. What reaches the model's system prompt ─────────────────────────────────

  @Test
  void adkAddsItsOwnInstructionsAroundTheAgentsPrompt() {
    Script ours = new Script().answer("Call emergency services immediately.");
    Script adk = new Script().answer("Call emergency services immediately.");

    new EvalRunner(props).run(List.of(scenario("vita-points-an-emergency-to-real-help")), 1, c -> ours);
    new EvalRunner(props, ADK).run(List.of(scenario("vita-points-an-emergency-to-real-help")), 1,
        c -> adk);

    List<String> oursSystem = systemTexts(ours.requests.get(0));
    List<String> adkSystem = systemTexts(adk.requests.get(0));
    assertThat(oursSystem.get(0)).startsWith("Your name is Vita");
    assertThat(adkSystem).anySatisfy(s -> assertThat(s).contains("You are not a clinician"));
    // Recorded for the decision: what ADK adds, and whether the agent's own prompt is still the
    // first, cacheable block.
    System.out.println("[spike] system blocks under ADK: " + adkSystem.size());
    for (String s : adkSystem) {
      System.out.println("[spike]   - starts: " + s.substring(0, Math.min(60, s.length())).replace('\n', ' '));
      System.out.println("[spike]   - ends:   " + s.substring(Math.max(0, s.length() - 160)).replace('\n', ' '));
      System.out.println("[spike]   - same as ours: " + s.equals(oursSystem.get(0)));
    }
  }

  private static List<String> systemTexts(List<Msg> messages) {
    return messages.stream().filter(m -> m.role() == Msg.Role.SYSTEM).map(Msg::text).toList();
  }

  // ── 5. Streaming and the stop button cross ADK's threads ──────────────────────

  @Test
  void jizzasRepliesStreamAndAStopPressedMidRunEndsIt() {
    List<ActivityEvent> events = new ArrayList<>();
    AtomicBoolean stop = new AtomicBoolean();
    ActivitySink sink = new ActivitySink() {
      @Override public void emit(ActivityEvent event) { events.add(event); }
      @Override public boolean cancelled() { return stop.get(); }
    };
    Script model = new Script().answer("Kai says your bill is 18,240 rupees.");

    Activity.run(sink, () -> new EvalRunner(props, ADK)
        .run(List.of(scenario("jizza-routes-money-to-kai")), 1, c -> model));

    System.out.println("[spike] model streamed on threads: " + model.streamedOnThread);
    assertThat(events).as("deltas reach the user's activity stream")
        .anySatisfy(e -> assertThat(e.type()).isEqualTo(ActivityTypes.DELTA));

    stop.set(true);
    Script second = new Script().answer("never");
    var trial = Activity.run(sink, () -> new EvalRunner(props, ADK)
        .run(List.of(scenario("jizza-routes-money-to-kai")), 1, c -> second));
    assertThat(second.requests).as("a pressed stop is seen before the model is called").isEmpty();
    assertThat(trial.cases().get(0).trials().get(0).failures())
        .anySatisfy(f -> assertThat(f).contains("JobCancelledException"));
  }
}
