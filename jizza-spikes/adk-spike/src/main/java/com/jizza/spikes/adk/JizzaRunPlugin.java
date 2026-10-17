package com.jizza.spikes.adk;

import com.google.adk.agents.CallbackContext;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.plugins.BasePlugin;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.jizza.engine.api.activity.Activity;
import com.jizza.engine.api.error.BudgetExceededException;
import com.jizza.engine.api.llm.CostAccountant;
import com.jizza.engine.api.llm.RunBudget;
import io.reactivex.rxjava3.core.Maybe;
import java.util.UUID;

/**
 * What Jizza's loop does around each model call, as ADK plugin hooks: a stop pressed by the user
 * ends the run, every call is costed, and the run budget (rounds and tokens) ends the turn with
 * what the agent has rather than an error. One instance per run.
 */
final class JizzaRunPlugin extends BasePlugin {

  private final UUID userId;
  private final String model;
  private final String purpose;
  private final CostAccountant costs;
  private final RunBudget budget;
  int inputTokens;
  int outputTokens;
  String stoppedBecause;

  JizzaRunPlugin(UUID userId, String model, String purpose, CostAccountant costs, RunBudget budget) {
    super("jizza-run");
    this.userId = userId;
    this.model = model;
    this.purpose = purpose;
    this.costs = costs;
    this.budget = budget;
  }

  @Override
  public Maybe<LlmResponse> beforeModelCallback(CallbackContext context, LlmRequest.Builder request) {
    Activity.checkCancelled();
    if (stoppedBecause != null) return Maybe.just(stopped());
    try {
      budget.nextIteration();
    } catch (BudgetExceededException e) {
      stoppedBecause = e.getMessage();
      return Maybe.just(stopped());
    }
    return Maybe.empty();
  }

  @Override
  public Maybe<LlmResponse> afterModelCallback(CallbackContext context, LlmResponse response) {
    if (response.partial().orElse(false)) return Maybe.empty();
    var usage = response.usageMetadata().orElse(null);
    if (usage == null) return Maybe.empty();
    int in = usage.promptTokenCount().orElse(0);
    int out = usage.candidatesTokenCount().orElse(0);
    int cacheRead = usage.cachedContentTokenCount().orElse(0);
    inputTokens += in;
    outputTokens += out;
    costs.record(userId, response.modelVersion().orElse(model), in, out, cacheRead, 0, purpose);
    try {
      budget.addTokens(in, out);
    } catch (BudgetExceededException e) {
      stoppedBecause = e.getMessage();
      // Keep what it said; drop the tool calls it wanted, so the run ends here.
      if (!response.content().flatMap(Content::parts).orElse(java.util.List.of()).stream()
          .allMatch(p -> p.functionCall().isEmpty())) {
        return Maybe.just(stopped());
      }
    }
    return Maybe.empty();
  }

  private LlmResponse stopped() {
    return LlmResponse.builder().content(Content.builder().role("model")
        .parts(Part.fromText("I stopped before finishing: " + stoppedBecause)).build()).build();
  }
}
