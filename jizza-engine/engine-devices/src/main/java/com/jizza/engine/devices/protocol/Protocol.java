package com.jizza.engine.devices.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.util.Json;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * The Jizza Agent Protocol, version 1: the envelope, the message types and the error codes. The
 * spec is {@code docs/agent-protocol-v1.md}; this class is its single source in code.
 */
public final class Protocol {

  private Protocol() {}

  // Session
  public static final String HELLO = "hello";
  public static final String WELCOME = "welcome";
  public static final String MANIFEST = "manifest";
  public static final String HEARTBEAT = "heartbeat";
  public static final String POLICY = "policy";
  public static final String BYE = "bye";
  // Work
  public static final String COMMAND = "command";
  public static final String ACCEPTED = "accepted";
  public static final String REJECTED = "rejected";
  public static final String PROGRESS = "progress";
  public static final String RESULT = "result";
  public static final String CANCEL = "cancel";
  // Things the device starts
  public static final String EVENT = "event";
  public static final String NEEDS_USER = "needs_user";
  public static final String ANSWER = "answer";
  public static final String LOG = "log";
  // Server to device: something in the person's inbox (phones show it and answer it)
  public static final String INBOX = "inbox";
  // Replies
  public static final String ACK = "ack";
  public static final String ERROR = "error";

  /** Messages a device may send. */
  public static final Set<String> FROM_DEVICE = Set.of(HELLO, MANIFEST, HEARTBEAT, ACCEPTED,
      REJECTED, PROGRESS, RESULT, EVENT, NEEDS_USER, ANSWER, LOG, BYE, ACK);

  /** Device messages the server confirms with an {@code ack}, so the device can stop resending. */
  public static final Set<String> ACKED = Set.of(MANIFEST, RESULT, EVENT, NEEDS_USER, ANSWER);

  /** The fixed error codes, so every app shows the same words. */
  public static final class Errors {
    private Errors() {}
    public static final String BAD_MESSAGE = "bad_message";
    public static final String UNSUPPORTED_TYPE = "unsupported_type";
    public static final String UNSUPPORTED_VERSION = "unsupported_version";
    public static final String UNAUTHORIZED = "unauthorized";
    public static final String NOT_FOUND = "not_found";
    public static final String TOO_LARGE = "too_large";
    public static final String RATE_LIMITED = "rate_limited";
    // Command outcomes
    public static final String POLICY_DENIED = "policy_denied";
    public static final String PERMISSION_MISSING = "permission_missing";
    public static final String BUSY = "busy";
    public static final String UNSUPPORTED = "unsupported";
    public static final String BAD_SIGNATURE = "bad_signature";
    public static final String EXPIRED = "expired";
    public static final String DEVICE_OFFLINE = "device_offline";
    public static final String TIMED_OUT = "timed_out";
    public static final String CANCELLED = "cancelled";
    public static final String FAILED = "failed";
    public static final String REVOKED = "revoked";
  }

  /** A fresh envelope. Server messages are signed later, over their body. */
  public static ObjectNode envelope(String type, UUID deviceId, JsonNode body, String replyTo) {
    ObjectNode env = Json.obj();
    env.put("v", 1);
    env.put("type", type);
    env.put("id", MessageIds.next());
    if (deviceId != null) env.put("deviceId", deviceId.toString());
    env.put("ts", Instant.now().toString());
    if (replyTo == null) env.putNull("replyTo"); else env.put("replyTo", replyTo);
    env.set("body", body == null ? Json.obj() : body);
    return env;
  }

  public static ObjectNode ack(UUID deviceId, String replyTo) {
    return envelope(ACK, deviceId, Json.obj(), replyTo);
  }

  public static ObjectNode error(UUID deviceId, String replyTo, String code, String message) {
    return envelope(ERROR, deviceId, Json.obj().put("code", code).put("message", message), replyTo);
  }
}
