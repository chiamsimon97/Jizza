package com.jizza.engine.api.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.enums.ActionType;

/**
 * One tool an MCP server offers.
 *
 * @param name       the server's own name for it
 * @param toolName   the name agents see, {@code mcp_<server>__<tool>}
 * @param actionType what the user says it does, or what Jizza suggested from its name; null when
 *                   neither, and then it is judged the most consequential kind (moving money)
 * @param suggested  true while {@code actionType} is Jizza's suggestion the user has not changed
 * @param outputSchema the shape of what it returns, as JSON Schema: the server's own, or learnt
 *                     from a run; null until either
 * @param outputSource {@code server} or {@code learnt}; null with no output schema
 * @param serverHint what the server claims about it ({@code read-only}, {@code destructive}), shown
 *                   to help the user decide and never used by the guardrails
 */
public record McpToolDto(String name, String toolName, String title, String description,
                         JsonNode inputSchema, boolean enabled, ActionType actionType,
                         boolean suggested, JsonNode outputSchema, String outputSource,
                         String serverHint) {}
