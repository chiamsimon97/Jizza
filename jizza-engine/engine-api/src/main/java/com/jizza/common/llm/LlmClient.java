package com.jizza.common.llm;

import com.jizza.common.tool.ToolSpec;
import java.util.List;

/** The single seam to the LLM provider. Model strings are configuration, never literals (R49). */
public interface LlmClient {

  LlmResponse complete(String model, List<Msg> messages, List<ToolSpec> tools);

  /** Convenience overload used by callers that only care about the tier. */
  default LlmResponse complete(com.jizza.common.enums.ModelTier tier, List<Msg> messages, List<ToolSpec> tools) {
    return complete(modelFor(tier), messages, tools);
  }

  /** Resolve the configured model string for a tier. */
  String modelFor(com.jizza.common.enums.ModelTier tier);
}
