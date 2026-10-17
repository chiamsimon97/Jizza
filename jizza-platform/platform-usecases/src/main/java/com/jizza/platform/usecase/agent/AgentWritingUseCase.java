package com.jizza.platform.usecase.agent;

import com.jizza.engine.api.enums.ModelTier;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.llm.CostAccountant;
import com.jizza.engine.api.llm.LlmClient;
import com.jizza.engine.api.llm.LlmResponse;
import com.jizza.engine.api.llm.Msg;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * "Improve these": rewrites an agent's instructions so they are clear and complete, keeping what
 * the user meant. The rewrite is only shown; the user decides whether to use it.
 */
@Service
public class AgentWritingUseCase {

  private static final String PROMPT = """
      You rewrite the instructions for an AI agent that works for one person as part of their
      personal assistant's team. Keep everything the author meant, including every "always" and
      "never". Make the instructions clear and specific: what the agent is for, how it should work,
      its tone, and what it must check with the person first. Use short paragraphs or a short
      list. Don't invent tools, permissions or facts about the person, and don't mention that you
      rewrote anything. Answer with the rewritten instructions only, under 3,500 characters.
      """;

  private final LlmClient llm;
  private final CostAccountant costs;

  public AgentWritingUseCase(LlmClient llm, CostAccountant costs) {
    this.llm = llm;
    this.costs = costs;
  }

  public String improve(UUID userId, String name, String useCase, String instructions) {
    if (instructions == null || instructions.isBlank()) {
      throw new ValidationException("Write some instructions first");
    }
    if (instructions.length() > CustomAgentUseCase.MAX_INSTRUCTIONS) {
      throw new ValidationException("Instructions are limited to "
          + CustomAgentUseCase.MAX_INSTRUCTIONS + " characters");
    }
    if (!costs.withinBudget(userId)) {
      throw new ValidationException("Your spending limit for this period has been reached");
    }
    String request = "Agent name: " + (name == null ? "(unnamed)" : name)
        + "\nWhen to use it: " + (useCase == null ? "(not given)" : useCase)
        + "\n\nInstructions to rewrite:\n" + instructions;
    LlmResponse response = llm.complete(ModelTier.STANDARD,
        List.of(Msg.system(PROMPT), Msg.user(request)), List.of());
    costs.record(userId, response.model(), response.inputTokens(), response.outputTokens(),
        "agent-instructions");
    String text = response.text() == null ? "" : response.text().strip();
    if (text.isEmpty()) throw new ValidationException("Couldn't rewrite them just now; try again");
    return text.length() > CustomAgentUseCase.MAX_INSTRUCTIONS
        ? text.substring(0, CustomAgentUseCase.MAX_INSTRUCTIONS) : text;
  }
}
