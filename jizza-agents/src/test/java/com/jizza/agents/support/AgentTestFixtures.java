package com.jizza.agents.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.common.autonomy.AutonomyGate;
import com.jizza.common.autonomy.Decision;
import com.jizza.common.autonomy.KillSwitch;
import com.jizza.common.enums.AutonomyRule;
import com.jizza.common.enums.ModelTier;
import com.jizza.common.error.AutonomyDeniedException;
import com.jizza.common.llm.LlmClient;
import com.jizza.common.llm.LlmResponse;
import com.jizza.common.llm.Msg;
import com.jizza.common.tool.ToolCall;
import com.jizza.common.tool.ToolProvider;
import com.jizza.common.tool.ToolResult;
import com.jizza.common.tool.ToolSpec;
import com.jizza.persistence.entity.AgentEntity;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Shared doubles for the agent-runtime tests. */
public final class AgentTestFixtures {

  private AgentTestFixtures() {}

  public static AgentEntity agent(String key, String name, boolean orchestrator) {
    AgentEntity agent = new AgentEntity();
    agent.setId(UUID.randomUUID());
    agent.setUserId(UUID.randomUUID());
    agent.setKey(key);
    agent.setName(name);
    agent.setSystemPrompt("You look after " + key + ".");
    agent.setOrchestrator(orchestrator);
    agent.setEnabled(true);
    return agent;
  }

  /** Replays a scripted sequence of responses and records what it was asked. */
  public static final class ScriptedLlm implements LlmClient {
    private final Deque<LlmResponse> script = new ArrayDeque<>();
    private final LlmResponse repeatingTail;
    public final List<List<Msg>> requests = new ArrayList<>();
    public final List<List<ToolSpec>> offeredTools = new ArrayList<>();

    public ScriptedLlm(List<LlmResponse> responses) { this(responses, null); }

    /** @param repeatingTail returned once the script runs out; null means fail loudly instead. */
    public ScriptedLlm(List<LlmResponse> responses, LlmResponse repeatingTail) {
      this.script.addAll(responses);
      this.repeatingTail = repeatingTail;
    }

    @Override public String modelFor(ModelTier tier) { return "test-model-" + tier; }

    @Override
    public LlmResponse complete(String model, List<Msg> messages, List<ToolSpec> tools) {
      requests.add(List.copyOf(messages));
      offeredTools.add(tools == null ? List.of() : List.copyOf(tools));
      if (!script.isEmpty()) return script.poll();
      if (repeatingTail != null) return repeatingTail;
      throw new IllegalStateException("The scripted model ran out of responses");
    }

    public int callCount() { return requests.size(); }
  }

  /** A tool provider whose invocations are observable. */
  public static class RecordingProvider implements ToolProvider {
    private final Map<String, ToolSpec> specs = new HashMap<>();
    public final List<ToolCall> invocations = new ArrayList<>();
    public ToolResult nextResult = ToolResult.ok("done");

    public RecordingProvider(ToolSpec... offered) {
      for (ToolSpec spec : offered) specs.put(spec.name(), spec);
    }

    @Override public List<ToolSpec> toolsFor(UUID agentId) { return List.copyOf(specs.values()); }

    @Override public boolean supports(UUID agentId, String toolName) { return specs.containsKey(toolName); }

    @Override
    public ToolResult invoke(UUID agentId, ToolCall call) {
      invocations.add(call);
      return nextResult;
    }
  }

  public static final class FixedGate implements AutonomyGate {
    public AutonomyRule rule = AutonomyRule.ALLOW;
    public boolean agentEnabled = true;

    @Override
    public Decision evaluate(UUID agentId, String toolRef, JsonNode args) {
      return switch (rule) {
        case ALLOW -> Decision.allow("test");
        case ASK -> Decision.ask("needs approval", "test");
        case DENY -> Decision.deny("blocked by policy", "test");
      };
    }

    @Override public boolean agentEnabled(UUID agentId) { return agentEnabled; }
  }

  public static final class TogglableKillSwitch implements KillSwitch {
    public boolean paused;

    @Override public boolean paused(UUID userId) { return paused; }

    @Override public void setPaused(UUID userId, boolean value) { this.paused = value; }

    @Override
    public void requireActive(UUID userId) {
      if (paused) throw new AutonomyDeniedException("Autonomy is paused by the kill switch");
    }
  }
}
