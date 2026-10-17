package com.jizza.engine.infra.realtime;

import com.jizza.engine.security.session.JizzaPrincipal;
import java.security.Principal;
import java.util.Map;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.security.core.Authentication;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

/**
 * Names each socket after the user who opened it, so {@code convertAndSendToUser(userId, ...)}
 * reaches that user's sessions and nobody else's.
 *
 * <p>The handshake is an ordinary authenticated request: the session cookie has already been
 * checked by the security filter chain, which leaves a {@link JizzaPrincipal} inside the request's
 * {@link Authentication}. Its default name is the record's {@code toString()}, which no sender could
 * address; the user id is.
 */
class UserIdHandshakeHandler extends DefaultHandshakeHandler {

  @Override
  protected Principal determineUser(ServerHttpRequest request, WebSocketHandler handler,
                                    Map<String, Object> attributes) {
    return userOf(request.getPrincipal());
  }

  static Principal userOf(Principal principal) {
    if (principal instanceof Authentication auth && auth.getPrincipal() instanceof JizzaPrincipal p) {
      String name = p.userId().toString();
      return () -> name;
    }
    // Unauthenticated handshakes are refused by the filter chain before they get here; anything
    // else gets no user destination at all rather than a guessable one.
    return null;
  }
}
