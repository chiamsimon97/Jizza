package com.jizza.platform.realtime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP over WebSocket for streaming replies and pushing notifications (R43).
 *
 * <p>A simple in-memory broker is the right size for a single-user, single-process deployment; the
 * relay configuration is the seam to swap in a real broker if this is ever hosted for many users.
 * The handshake shares the servlet session, so the same cookie that authenticates the REST API
 * authenticates the socket.
 */
@Configuration
@EnableWebSocketMessageBroker
public class RealtimeConfig implements WebSocketMessageBrokerConfigurer {

  private final String[] allowedOrigins;

  public RealtimeConfig(@Value("${jizza.security.cors.allowed-origins:http://localhost:5173}")
                        String[] allowedOrigins) {
    this.allowedOrigins = allowedOrigins;
  }

  @Override
  public void configureMessageBroker(MessageBrokerRegistry registry) {
    registry.enableSimpleBroker("/topic", "/queue");
    registry.setApplicationDestinationPrefixes("/app");
    registry.setUserDestinationPrefix("/user");
  }

  @Override
  public void registerStompEndpoints(StompEndpointRegistry registry) {
    registry.addEndpoint("/ws").setAllowedOriginPatterns(allowedOrigins);
  }
}
