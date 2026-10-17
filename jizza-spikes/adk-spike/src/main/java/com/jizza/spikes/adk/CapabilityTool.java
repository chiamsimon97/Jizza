package com.jizza.spikes.adk;

import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.genai.types.FunctionDeclaration;
import com.jizza.engine.api.agent.CapabilityRegistry;
import com.jizza.engine.api.tool.ToolCall;
import com.jizza.engine.api.tool.ToolResult;
import com.jizza.engine.api.tool.ToolSpec;
import com.jizza.engine.api.util.Json;
import io.reactivex.rxjava3.core.Single;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * One of an agent's tools as ADK sees it. Running it does nothing but hand the call to the
 * Capability Registry, the single choke point (R28): agent enabled, kill switch, schema, account
 * access, guardrails and audit all happen there, exactly as in Jizza's own loop. ADK's
 * before-tool hooks are deliberately not used for any of that, so there is one place it happens.
 */
public final class CapabilityTool extends BaseTool {

  private final ToolSpec spec;
  private final CapabilityRegistry registry;
  private final UUID agentId;
  private final boolean autonomous;

  public CapabilityTool(ToolSpec spec, CapabilityRegistry registry, UUID agentId, boolean autonomous) {
    super(spec.name(), spec.description() == null ? "" : spec.description());
    this.spec = spec;
    this.registry = registry;
    this.agentId = agentId;
    this.autonomous = autonomous;
  }

  ToolSpec spec() { return spec; }

  @Override
  public Optional<FunctionDeclaration> declaration() {
    return Optional.of(FunctionDeclaration.builder().name(spec.name())
        .description(spec.description() == null ? "" : spec.description())
        .parametersJsonSchema(Json.mapper().convertValue(spec.inputSchema(), Map.class))
        .build());
  }

  @Override
  public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext context) {
    return Single.fromCallable(() -> {
      String id = context.functionCallId().orElse(UUID.randomUUID().toString());
      ToolResult result = registry.invoke(agentId, new ToolCall(id, spec.name(), Json.toNode(args)),
          autonomous);
      Map<String, Object> out = new LinkedHashMap<>();
      out.put("ok", result.ok());
      if (result.ok()) out.put("output", Json.mapper().convertValue(result.output(), Object.class));
      else out.put("error", result.error());
      return out;
    });
  }
}
