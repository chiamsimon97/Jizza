package com.jizza.engine.agents.registry;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The service calls a conversation's jobs made, with the arguments they were made with, so a job
 * the user wants to keep can be saved as the exact steps that did it rather than as instructions
 * for an agent to work out again.
 *
 * <p>Kept in memory, briefly, and only for chat conversations: it serves "save what you just did",
 * nothing else reads it. Only calls that succeeded are kept, and a call whose arguments are never
 * shown (a provider that redacts them) is kept without them.
 */
@Component
public class ServiceCallJournal {

  /** How long a conversation's calls are kept after its last one. */
  static final Duration KEEP = Duration.ofHours(2);
  static final int PER_CONVERSATION = 40;
  static final int CONVERSATIONS = 1_000;

  /**
   * @param arguments what the call was made with; null when its provider keeps them hidden
   */
  public record Call(UUID agentId, String agentName, String tool, JsonNode arguments, Instant at) {}

  private final Clock clock;
  private final Map<UUID, Deque<Call>> byConversation =
      new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, Deque<Call>> eldest) {
          return size() > CONVERSATIONS;
        }
      };

  @Autowired
  public ServiceCallJournal() {
    this(Clock.systemUTC());
  }

  ServiceCallJournal(Clock clock) {
    this.clock = clock;
  }

  public synchronized void record(UUID conversationId, UUID agentId, String agentName, String tool,
                                  JsonNode arguments) {
    if (conversationId == null || tool == null) return;
    Deque<Call> calls = byConversation.computeIfAbsent(conversationId, id -> new ArrayDeque<>());
    calls.addLast(new Call(agentId, agentName, tool,
        arguments == null ? null : arguments.deepCopy(), clock.instant()));
    while (calls.size() > PER_CONVERSATION) calls.removeFirst();
  }

  /** The conversation's recent calls, oldest first. */
  public synchronized List<Call> recent(UUID conversationId) {
    if (conversationId == null) return List.of();
    Deque<Call> calls = byConversation.get(conversationId);
    if (calls == null || calls.isEmpty()) return List.of();
    if (calls.peekLast().at().plus(KEEP).isBefore(clock.instant())) {
      byConversation.remove(conversationId);
      return List.of();
    }
    return List.copyOf(calls);
  }
}
