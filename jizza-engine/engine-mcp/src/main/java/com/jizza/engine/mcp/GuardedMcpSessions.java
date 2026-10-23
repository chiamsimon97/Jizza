package com.jizza.engine.mcp;

import com.jizza.engine.api.connector.EgressGuard;
import java.net.URI;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Opens sessions, first putting the address of a server added in the app through the egress guard:
 * https only, and never a private, loopback or metadata address, exactly as a connector's calls.
 * A configured server is the owner's own decision and may be local, so it is not checked.
 */
public class GuardedMcpSessions implements McpSession.Factory {

  private final McpSession.Factory delegate;
  private final ObjectProvider<EgressGuard> egress;

  public GuardedMcpSessions(McpSession.Factory delegate, ObjectProvider<EgressGuard> egress) {
    this.delegate = delegate;
    this.egress = egress;
  }

  @Override
  public McpSession open(String name, McpProperties.Server server) {
    if (server.getId() != null) {
      if (server.getTransport() != McpProperties.Transport.HTTP) {
        throw new IllegalStateException("A server added in the app can only be reached over HTTP");
      }
      EgressGuard guard = egress.getIfAvailable();
      if (guard == null) {
        throw new IllegalStateException("The egress guard is unavailable, so the server is not reached");
      }
      guard.check(URI.create(server.getUrl()), null);
    }
    return delegate.open(name, server);
  }
}
