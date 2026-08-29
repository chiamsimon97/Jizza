package com.jizza.engine.api.connector;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.dto.GeneratedToolDto;
import com.jizza.engine.api.tool.ToolResult;

/** SSRF-guarded, auth-injecting, idempotent HTTP caller for generated tools (R8/R27). */
public interface ToolExecutor {
  ToolResult execute(GeneratedToolDto tool, JsonNode args, IdempotencyKey key);
}
