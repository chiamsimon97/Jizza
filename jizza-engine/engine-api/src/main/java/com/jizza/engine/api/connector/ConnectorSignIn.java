package com.jizza.engine.api.connector;

import java.util.UUID;

/**
 * Says that a connector lost its sign-in (a token refresh failed, or the provider refused it), so
 * the person hears about it where they are: a card on the chat reply that ran into it, and one
 * item in the inbox until they sign in again, rather than only a failed tool call.
 */
public interface ConnectorSignIn {

  /** The kinds of connector, as the Connectors page names them in its addresses. */
  String SERVICE = "service";
  String MCP = "mcp";

  /**
   * The connector {@code id} of {@code kind} needs the person to sign in again.
   *
   * @param name what the person calls it, shown on the card and the inbox item
   */
  void lost(UUID userId, String kind, UUID id, String name);

  /** Where in the app the person signs in to it again. */
  static String path(String kind, UUID id) {
    return MCP.equals(kind) ? "/connectors/mcp/" + id : "/connectors/" + id + "/authentication";
  }
}
