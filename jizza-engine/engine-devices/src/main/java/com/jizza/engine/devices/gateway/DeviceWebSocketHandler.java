package com.jizza.engine.devices.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.device.DeviceAgentGateway;
import com.jizza.engine.api.error.AuthException;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.devices.broker.CommandBroker;
import com.jizza.engine.devices.config.DeviceProperties;
import com.jizza.engine.devices.protocol.Protocol;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * {@code /agent/v1/ws}: the protocol over a plain WebSocket, one JSON envelope per text frame.
 *
 * <p>A device signs in with its access token, either as {@code Authorization: Bearer} on the
 * handshake or in its first message, a {@code hello} whose body carries {@code token}. Until then
 * nothing else is accepted, and a socket that does not sign in within ten seconds is closed.
 */
@Component
public class DeviceWebSocketHandler extends TextWebSocketHandler {

  private static final Logger log = LoggerFactory.getLogger(DeviceWebSocketHandler.class);
  static final String CALLER = "jizza.device.caller";
  private static final long SIGN_IN_WINDOW_MS = 10_000;
  /** Close code for "sign in again" (application range). */
  static final CloseStatus UNAUTHORIZED = new CloseStatus(4401, "unauthorized");

  private final DeviceAgentGateway gateway;
  private final CommandBroker broker;
  private final DeviceHub hub;
  private final DeviceProperties props;

  public DeviceWebSocketHandler(DeviceAgentGateway gateway, CommandBroker broker, DeviceHub hub,
                                DeviceProperties props) {
    this.gateway = gateway;
    this.broker = broker;
    this.hub = hub;
    this.props = props;
  }

  @Override
  public void afterConnectionEstablished(WebSocketSession session) {
    session.setTextMessageSizeLimit(props.getMaxMessageBytes());
    String header = session.getHandshakeHeaders().getFirst(HttpHeaders.AUTHORIZATION);
    if (header != null && header.startsWith("Bearer ")) {
      try {
        signIn(session, header.substring(7).trim());
      } catch (AuthException e) {
        closeQuietly(session, UNAUTHORIZED);
        return;
      }
    }
    session.getAttributes().put("openedAt", System.currentTimeMillis());
  }

  @Override
  protected void handleTextMessage(WebSocketSession session, TextMessage message) throws IOException {
    JsonNode envelope;
    try {
      envelope = Json.mapper().readTree(message.getPayload());
    } catch (IOException e) {
      send(session, Protocol.error(null, null, Protocol.Errors.BAD_MESSAGE, "Not JSON"));
      return;
    }
    DeviceAgentGateway.Caller caller = (DeviceAgentGateway.Caller) session.getAttributes().get(CALLER);
    if (caller == null) {
      boolean hello = Protocol.HELLO.equals(envelope.path("type").asText());
      String token = envelope.path("body").path("token").asText(null);
      long openedAt = (long) session.getAttributes().getOrDefault("openedAt", 0L);
      if (!hello || token == null || System.currentTimeMillis() - openedAt > SIGN_IN_WINDOW_MS) {
        send(session, Protocol.error(null, envelope.path("id").asText(null),
            Protocol.Errors.UNAUTHORIZED, "Sign in first: send hello with your access token"));
        closeQuietly(session, UNAUTHORIZED);
        return;
      }
      try {
        caller = signIn(session, token);
      } catch (AuthException e) {
        send(session, Protocol.error(null, envelope.path("id").asText(null),
            Protocol.Errors.UNAUTHORIZED, e.getMessage()));
        closeQuietly(session, UNAUTHORIZED);
        return;
      }
    }
    // The token is checked again on every message, so a revoked or expired device is cut off.
    try {
      gateway.authenticate((String) session.getAttributes().get("token"));
    } catch (AuthException e) {
      send(session, Protocol.error(caller.deviceId(), envelope.path("id").asText(null),
          Protocol.Errors.UNAUTHORIZED, e.getMessage()));
      closeQuietly(session, UNAUTHORIZED);
      return;
    }
    JsonNode reply = gateway.receive(caller, envelope);
    if (reply != null) send(session, reply);
    if (Protocol.HELLO.equals(envelope.path("type").asText())
        && (reply == null || !Protocol.ERROR.equals(reply.path("type").asText()))) {
      broker.sendPending(caller.deviceId());
      for (JsonNode queued : hub.drain(caller.deviceId())) send(session, queued);
    }
    if (Protocol.BYE.equals(envelope.path("type").asText())) {
      closeQuietly(session, CloseStatus.NORMAL);
    }
  }

  private DeviceAgentGateway.Caller signIn(WebSocketSession session, String token) {
    DeviceAgentGateway.Caller caller = gateway.authenticate(token);
    WebSocketSession safe = new ConcurrentWebSocketSessionDecorator(session, 10_000,
        props.getMaxMessageBytes() * 4);
    session.getAttributes().put(CALLER, caller);
    session.getAttributes().put("token", token);
    session.getAttributes().put("safe", safe);
    hub.attach(caller.deviceId(), new DeviceHub.Channel() {
      @Override
      public boolean send(String text) {
        try {
          safe.sendMessage(new TextMessage(text));
          return true;
        } catch (IOException | IllegalStateException e) {
          return false;
        }
      }

      @Override
      public String id() {
        return session.getId();
      }

      @Override
      public void close(String reason) {
        closeQuietly(session, new CloseStatus(4000, reason.length() > 100 ? reason.substring(0, 100) : reason));
      }
    });
    session.getAttributes().put("channelDevice", caller.deviceId());
    return caller;
  }

  @Override
  public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
    Object deviceId = session.getAttributes().get("channelDevice");
    if (deviceId instanceof UUID id) {
      // Only forget the channel if it is still this session's; a newer socket may have replaced it.
      hub.detachSession(id, session.getId());
    }
  }

  @Override
  public void handleTransportError(WebSocketSession session, Throwable exception) {
    log.debug("Device socket error", exception);
  }

  private static void send(WebSocketSession session, JsonNode envelope) {
    WebSocketSession target = session.getAttributes().get("safe") instanceof WebSocketSession s
        ? s : session;
    try {
      if (target.isOpen()) target.sendMessage(new TextMessage(Json.write(envelope)));
    } catch (IOException | IllegalStateException e) {
      log.debug("Could not send to a device socket", e);
    }
  }

  private static void closeQuietly(WebSocketSession session, CloseStatus status) {
    try {
      if (session.isOpen()) session.close(status);
    } catch (IOException e) {
      log.debug("Could not close a device socket", e);
    }
  }
}
