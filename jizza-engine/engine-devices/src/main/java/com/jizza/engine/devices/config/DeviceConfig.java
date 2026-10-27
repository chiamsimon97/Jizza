package com.jizza.engine.devices.config;

import com.jizza.engine.devices.gateway.DeviceWebSocketHandler;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * The device socket at {@code /agent/v1/ws}: a plain WebSocket, beside the browser's STOMP socket
 * at {@code /ws}. It is not the browser's socket because it does not sign in with a cookie; device
 * apps are not browsers, so any origin may open it and the access token is what admits it.
 */
@Configuration
@EnableWebSocket
@EnableConfigurationProperties(DeviceProperties.class)
public class DeviceConfig implements WebSocketConfigurer {

  public static final String SOCKET_PATH = "/agent/v1/ws";

  private final DeviceWebSocketHandler handler;

  public DeviceConfig(DeviceWebSocketHandler handler) {
    this.handler = handler;
  }

  @Override
  public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
    registry.addHandler(handler, SOCKET_PATH).setAllowedOriginPatterns("*");
  }
}
