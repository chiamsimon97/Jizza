package com.jizza.engine.api.mcp;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A remote MCP server added from the app. Secrets never leave the engine: only whether one is set.
 *
 * @param status {@code new} (never reached), {@code connected}, {@code needs_auth} (OAuth sign-in
 *               needed) or {@code error} (see {@code lastError})
 * @param signInTool the tool by which the server signs the person in inside its own session
 *                   (Zerodha Kite's {@code login}), when it has no other sign-in; else null
 * @param sessionSignInAt when that tool last answered in the session open now; null when it has
 *                        not since the session opened, which includes every restart of Jizza
 */
public record McpServerDto(UUID id, UUID userId, UUID workspaceId, String name, String slug,
                           String url, McpAuthType authType, String headerName,
                           boolean secretPresent, boolean signedIn, boolean enabled,
                           List<String> agentKeys, boolean redactArguments, String status,
                           String lastError, String serverName, String serverVersion,
                           List<McpToolDto> tools, Instant toolsRefreshedAt, Instant createdAt,
                           String signInTool, Instant sessionSignInAt) {}
