package com.jizza.engine.mcp;

import java.util.Locale;

/** Plain words for the ways an MCP server turns Jizza away. */
final class McpErrors {

  private McpErrors() {}

  /**
   * True when the server refused for want of a sign-in (HTTP 401 or 403). The SDK reports that as
   * "Client failed to initialize" with the real reason a few causes down, so the chain is walked.
   */
  static boolean needsSignIn(Throwable error) {
    int depth = 0;
    for (Throwable t = error; t != null && depth < 8; t = t.getCause(), depth++) {
      if (t.getClass().getSimpleName().contains("AuthorizationException")) return true;
      String m = t.getMessage() == null ? "" : t.getMessage().toLowerCase(Locale.ROOT);
      if (m.contains("authorization error") || m.contains("unauthorized") || m.contains("no bearer token")
          || m.matches(".*\\b(401|403)\\b.*")) {
        return true;
      }
    }
    return false;
  }

  /** What to tell the person when a tool call was refused for want of a sign-in. */
  static final String SIGN_IN = "The server asks you to sign in first. Open it on Connectors and press Sign in."
      + " If there is no Sign in button, edit the server and choose OAuth (sign in with the service).";
}
