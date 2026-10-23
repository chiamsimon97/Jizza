package com.jizza.engine.api.mcp;

/**
 * How Jizza signs in to a remote MCP server.
 *
 * <p>{@code NONE} suits servers that handle sign-in themselves, such as Zerodha's Kite server,
 * whose {@code login} tool hands back a link to open. {@code BEARER} sends a token as
 * {@code Authorization: Bearer}, {@code HEADER} sends a secret under a header the user names,
 * and {@code OAUTH} runs the MCP authorization flow (discovery, client registration and PKCE).
 */
public enum McpAuthType { NONE, BEARER, HEADER, OAUTH }
