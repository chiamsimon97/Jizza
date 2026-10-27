package com.jizza.engine.devices.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.devices.config.DeviceProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Who is connected and how to reach them: an open socket per device, or a queue that the device's
 * next poll drains. Presence lives in memory, which matches the single-process server Jizza is
 * today; the database is still the source of truth for commands, so losing this map on a restart
 * loses nothing but a few seconds of "online".
 */
@Component
public class DeviceHub {

  private static final Logger log = LoggerFactory.getLogger(DeviceHub.class);
  /** Messages kept for a polling device that has not polled yet; older ones are dropped. */
  private static final int MAX_PENDING = 200;

  /** One open socket. */
  public interface Channel {
    boolean send(String text);

    void close(String reason);

    /** Tells two sockets of one device apart. */
    default String id() {
      return null;
    }
  }

  public record Presence(Instant lastSeen, String state, boolean socket) {}

  private final DeviceProperties props;
  private final Clock clock;
  private final Map<UUID, Channel> sockets = new ConcurrentHashMap<>();
  private final Map<UUID, Deque<JsonNode>> pending = new ConcurrentHashMap<>();
  private final Map<UUID, Presence> presence = new ConcurrentHashMap<>();
  private final Map<UUID, Object> monitors = new ConcurrentHashMap<>();

  @org.springframework.beans.factory.annotation.Autowired
  public DeviceHub(DeviceProperties props) {
    this(props, Clock.systemUTC());
  }

  DeviceHub(DeviceProperties props, Clock clock) {
    this.props = props;
    this.clock = clock;
  }

  /** A device opened a socket; an older one of the same device is closed. */
  public void attach(UUID deviceId, Channel channel) {
    Channel previous = sockets.put(deviceId, channel);
    if (previous != null && previous != channel) previous.close("replaced by a newer connection");
    touch(deviceId, null);
  }

  public void detach(UUID deviceId, Channel channel) {
    sockets.remove(deviceId, channel);
    Presence p = presence.get(deviceId);
    if (p != null) presence.put(deviceId, new Presence(p.lastSeen(), p.state(), false));
  }

  /** A socket closed: forgotten only if it is still the device's current one. */
  public void detachSession(UUID deviceId, String channelId) {
    Channel current = sockets.get(deviceId);
    if (current != null && channelId != null && channelId.equals(current.id())) {
      detach(deviceId, current);
    }
  }

  public boolean socketOpen(UUID deviceId) {
    return sockets.containsKey(deviceId);
  }

  /** Connected by socket, or heard from (a poll, a message) within three heartbeats. */
  public boolean online(UUID deviceId) {
    if (sockets.containsKey(deviceId)) return true;
    Presence p = presence.get(deviceId);
    return p != null && p.lastSeen().isAfter(clock.instant().minus(staleAfter()));
  }

  public Set<UUID> onlineDevices() {
    return Set.copyOf(presence.keySet().stream().filter(this::online).toList());
  }

  public String state(UUID deviceId) {
    Presence p = presence.get(deviceId);
    return p == null || p.state() == null ? "idle" : p.state();
  }

  public Instant lastSeen(UUID deviceId) {
    Presence p = presence.get(deviceId);
    return p == null ? null : p.lastSeen();
  }

  /** The device was heard from; {@code state} is what its heartbeat said, or null to keep it. */
  public void touch(UUID deviceId, String state) {
    presence.compute(deviceId, (id, p) -> new Presence(clock.instant(),
        state != null ? state : p == null ? "idle" : p.state(), sockets.containsKey(id)));
  }

  /**
   * Sends now over the device's socket, or keeps the message for its next poll.
   *
   * @return true when it went out over a socket
   */
  public boolean deliver(UUID deviceId, JsonNode envelope) {
    Channel channel = sockets.get(deviceId);
    if (channel != null) {
      if (channel.send(Json.write(envelope))) return true;
      detach(deviceId, channel);
    }
    Deque<JsonNode> queue = pending.computeIfAbsent(deviceId, id -> new ArrayDeque<>());
    synchronized (queue) {
      if (queue.size() >= MAX_PENDING) queue.pollFirst();
      queue.addLast(envelope);
    }
    signal(deviceId);
    return false;
  }

  /** What is kept for a polling device, emptied. */
  public List<JsonNode> drain(UUID deviceId) {
    Deque<JsonNode> queue = pending.get(deviceId);
    if (queue == null) return List.of();
    synchronized (queue) {
      List<JsonNode> out = new ArrayList<>(queue);
      queue.clear();
      return out;
    }
  }

  public boolean hasPending(UUID deviceId) {
    Deque<JsonNode> queue = pending.get(deviceId);
    if (queue == null) return false;
    synchronized (queue) {
      return !queue.isEmpty();
    }
  }

  /** Holds a long poll until something is queued for the device, or {@code wait} passes. */
  public void await(UUID deviceId, Duration wait) {
    Object monitor = monitors.computeIfAbsent(deviceId, id -> new Object());
    synchronized (monitor) {
      try {
        monitor.wait(Math.max(1, wait.toMillis()));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  /** Something is waiting for the device: release its long poll. */
  public void signal(UUID deviceId) {
    Object monitor = monitors.get(deviceId);
    if (monitor == null) return;
    synchronized (monitor) {
      monitor.notifyAll();
    }
  }

  /** Closes the device's socket and forgets it, after revoking or disabling it. */
  public void disconnect(UUID deviceId, String reason) {
    Channel channel = sockets.remove(deviceId);
    if (channel != null) {
      log.info("Closing device {}: {}", deviceId, reason);
      channel.close(reason);
    }
    pending.remove(deviceId);
    presence.remove(deviceId);
    signal(deviceId);
  }

  /** Devices last heard from long ago, forgotten so the map stays small. */
  public void forgetStale() {
    Instant cutoff = clock.instant().minus(staleAfter().multipliedBy(20));
    presence.entrySet().removeIf(e -> !sockets.containsKey(e.getKey())
        && e.getValue().lastSeen().isBefore(cutoff));
  }

  private Duration staleAfter() {
    Duration poll = props.getMaxPollWait().plusSeconds(5);
    Duration heartbeats = props.getHeartbeat().multipliedBy(3);
    return poll.compareTo(heartbeats) > 0 ? poll : heartbeats;
  }
}
