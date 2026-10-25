package com.jizza.engine.api.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * A conversation as the conversation list shows it.
 *
 * @param kind      {@code main} for a chat with Jizza, {@code thread} for a topic handed to a lead
 * @param parentId  for a thread, the main chat it was opened from
 * @param lead      for a thread, the agent leading it (Jizza when no specialist does)
 * @param topic     for a thread, what it is about
 * @param threadsEnabled for a main chat, whether the user turned threads on for it
 * @param stage     where the conversation stands: {@code working} while a reply is being worked
 *                  on, {@code waiting} when the last reply asks the user something or awaits their
 *                  approval, {@code resolved} once archived, {@code idle} otherwise
 */
public record ConversationSummaryDto(UUID id, String title, String preview, Instant createdAt,
                                     Instant lastMessageAt, boolean pinned, boolean archived,
                                     int messageCount, String kind, UUID parentId, AgentRef lead,
                                     String topic, boolean threadsEnabled, String stage) {

  public static final String WORKING = "working";
  public static final String WAITING = "waiting";
  public static final String IDLE = "idle";
  public static final String RESOLVED = "resolved";

  /** An agent as a list row needs it: its id, the key its logo is drawn from and its name. */
  public record AgentRef(UUID id, String key, String name) {}
}
