package com.jizza.engine.api.mcp;

import java.util.List;

/**
 * What an MCP server is configured with, as the platform hands it to the engine. A null field
 * means "leave what is stored alone", so a server can be edited without retyping its secret.
 *
 * @param secret     the bearer token or header value; plaintext on the way in only
 * @param agentKeys  the keys of the agents that may use the server ({@code jizza}, {@code finance},
 *                   ...), or {@code *} for all of them
 * @param oauthClientId     for OAUTH servers that do not register clients themselves
 * @param oauthClientSecret plaintext on the way in only
 * @param transport  {@code streamable_http} (default), {@code sse} for older servers, or
 *                   {@code device} for a server that runs on a paired desktop app
 * @param deviceId   for a device server: the paired app it runs on
 * @param deviceServer for a device server: its name in that app
 */
public record McpServerSettings(String name, String url, McpAuthType authType, String headerName,
                                String secret, Boolean enabled, List<String> agentKeys,
                                Boolean redactArguments, String oauthClientId,
                                String oauthClientSecret, String oauthScopes, String transport,
                                java.util.UUID deviceId, String deviceServer) {

  public static final String STREAMABLE_HTTP = "streamable_http";
  public static final String SSE = "sse";
  public static final String DEVICE = "device";

  public McpServerSettings(String name, String url, McpAuthType authType, String headerName,
                           String secret, Boolean enabled, List<String> agentKeys,
                           Boolean redactArguments, String oauthClientId,
                           String oauthClientSecret, String oauthScopes) {
    this(name, url, authType, headerName, secret, enabled, agentKeys, redactArguments,
        oauthClientId, oauthClientSecret, oauthScopes, null, null, null);
  }
}
