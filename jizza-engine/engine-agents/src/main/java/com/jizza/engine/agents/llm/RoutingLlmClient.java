package com.jizza.engine.agents.llm;

import com.jizza.engine.api.enums.ModelTier;
import com.jizza.engine.api.error.JizzaException;
import com.jizza.engine.api.llm.LlmCallContext;
import com.jizza.engine.api.llm.LlmClient;
import com.jizza.engine.api.llm.LlmResponse;
import com.jizza.engine.api.llm.ModelRefs;
import com.jizza.engine.api.llm.Msg;
import com.jizza.engine.api.tool.ToolSpec;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;

/**
 * The provider registry behind the single {@link LlmClient} seam (F1). A bare model id goes to the
 * platform's own client as before; a model named {@code <provider>:<id>} goes to the person's own
 * connection to that provider, found through the run the call belongs to ({@link LlmCallContext}).
 * The reply names the model the way it was asked for, so usage is recorded against the person's
 * own key and priced from their catalog.
 */
public class RoutingLlmClient implements LlmClient {

  private final LlmClient platform;
  private final Supplier<ModelProviders> providers;

  public RoutingLlmClient(LlmClient platform, Supplier<ModelProviders> providers) {
    this.platform = platform;
    this.providers = providers;
  }

  /** The client that serves platform models. */
  public LlmClient platform() { return platform; }

  @Override
  public String modelFor(ModelTier tier) {
    return platform.modelFor(tier);
  }

  @Override
  public LlmResponse complete(String model, List<Msg> messages, List<ToolSpec> tools) {
    var ref = ModelRefs.parse(model);
    if (ref.isEmpty()) return platform.complete(model, messages, tools);
    return named(model, clientFor(ref.get()).complete(ref.get().modelId(), messages, tools));
  }

  @Override
  public LlmResponse stream(String model, List<Msg> messages, List<ToolSpec> tools,
                            Consumer<String> onText) {
    var ref = ModelRefs.parse(model);
    if (ref.isEmpty()) return platform.stream(model, messages, tools, onText);
    return named(model, clientFor(ref.get()).stream(ref.get().modelId(), messages, tools, onText));
  }

  private LlmClient clientFor(ModelRefs.Ref ref) {
    UUID userId = LlmCallContext.userId().orElseThrow(() -> new JizzaException(
        HttpStatus.BAD_REQUEST, "llm-own-key-outside-run", "Model not available",
        "A model on your own key can only answer your own requests."));
    ModelProviders registry = providers.get();
    if (registry == null) {
      throw new JizzaException(HttpStatus.SERVICE_UNAVAILABLE, "llm-providers-unavailable",
          "Model not available", "Your own AI providers can't be reached right now.");
    }
    return registry.clientFor(userId, ref.provider());
  }

  private static LlmResponse named(String ref, LlmResponse r) {
    return new LlmResponse(r.text(), r.toolCalls(), r.stopReason(), r.inputTokens(),
        r.outputTokens(), ref, r.cacheReadTokens(), r.cacheWriteTokens(), r.providerContent());
  }
}
