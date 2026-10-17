package com.jizza.engine.api.llm;

import com.jizza.engine.api.tool.ToolSpec;
import java.util.List;

/** The single seam to the LLM provider. Model strings are configuration, never literals (R49). */
public interface LlmClient {

  LlmResponse complete(String model, List<Msg> messages, List<ToolSpec> tools);

  /**
   * The same call, handing text to {@code onText} as the model writes it. The returned response is
   * complete, exactly as {@link #complete} would have returned it. A client that cannot stream
   * hands over the whole text at once.
   */
  default LlmResponse stream(String model, List<Msg> messages, List<ToolSpec> tools,
                             java.util.function.Consumer<String> onText) {
    LlmResponse response = complete(model, messages, tools);
    if (response.text() != null && !response.text().isEmpty()) onText.accept(response.text());
    return response;
  }

  /** Convenience overload used by callers that only care about the tier. */
  default LlmResponse complete(com.jizza.engine.api.enums.ModelTier tier, List<Msg> messages, List<ToolSpec> tools) {
    return complete(modelFor(tier), messages, tools);
  }

  /** Resolve the configured model string for a tier. */
  String modelFor(com.jizza.engine.api.enums.ModelTier tier);
}
