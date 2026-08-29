package com.jizza.common.connector;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.common.dto.GeneratedToolDto;
import com.jizza.common.tool.ToolResult;

/** SSRF-guarded, auth-injecting, idempotent HTTP caller for generated tools (R8/R27). */
public interface ToolExecutor {
  ToolResult execute(GeneratedToolDto tool, JsonNode args, IdempotencyKey key);
}
