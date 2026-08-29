package com.jizza.agents.llm;

import com.jizza.common.enums.ModelTier;
import com.jizza.common.error.JizzaException;
import com.jizza.common.llm.LlmClient;
import com.jizza.common.llm.LlmResponse;
import com.jizza.common.llm.Msg;
import com.jizza.common.tool.ToolSpec;
import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * Stands in when no API key is configured, so the application still boots and every non-LLM feature
 * remains testable. It fails loudly on use rather than quietly returning something plausible.
 */
public class UnconfiguredLlmClient implements LlmClient {

  @Override
  public String modelFor(ModelTier tier) { return "unconfigured"; }

  @Override
  public LlmResponse complete(String model, List<Msg> messages, List<ToolSpec> tools) {
    throw new JizzaException(HttpStatus.SERVICE_UNAVAILABLE, "llm-unconfigured",
        "Model access is not configured",
        "Set ANTHROPIC_API_KEY (jizza.agents.anthropic.api-key) to enable agent replies.");
  }
}
