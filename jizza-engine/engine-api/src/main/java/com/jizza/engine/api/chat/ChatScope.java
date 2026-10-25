package com.jizza.engine.api.chat;

import com.jizza.engine.api.orchestrator.UiAction;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Where a chat turn is happening: the main chat, where Jizza coordinates, or a thread led by one
 * agent. Current on the thread that runs the turn, so the tools offered to the agent answering it
 * can depend on where it is (opening a thread from the main chat, handing a message back from a
 * thread) without every layer passing the conversation along.
 *
 * <p>Deliberately not inherited: a specialist Jizza delegates to on another thread sees no scope and
 * so none of the thread tools. Only the agent answering the user in this conversation gets them.
 */
public final class ChatScope {

  /**
   * @param responderAgentId the agent answering the user in this turn: Jizza in the main chat, the
   *                         thread's lead in a thread
   * @param parentId         for a thread, the main chat it was opened from
   * @param title            for a thread, its title
   * @param topic            for a thread, what it is about
   */
  public record Scope(UUID userId, UUID conversationId, boolean thread, UUID parentId,
                      UUID responderAgentId, String title, String topic, List<UiAction> actions) {

    public static Scope main(UUID userId, UUID conversationId, UUID jizzaId) {
      return new Scope(userId, conversationId, false, null, jizzaId, null, null, new ArrayList<>());
    }

    public static Scope thread(UUID userId, UUID conversationId, UUID parentId, UUID leadId,
                               String title, String topic) {
      return new Scope(userId, conversationId, true, parentId, leadId, title, topic, new ArrayList<>());
    }
  }

  private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();

  private ChatScope() {}

  public static Optional<Scope> current() {
    return Optional.ofNullable(CURRENT.get());
  }

  public static <T> T run(Scope scope, Supplier<T> work) {
    Scope previous = CURRENT.get();
    CURRENT.set(scope);
    try {
      return work.get();
    } finally {
      if (previous == null) CURRENT.remove();
      else CURRENT.set(previous);
    }
  }

  /** True when this agent is the one answering the user in the current turn. */
  public static boolean isResponder(UUID agentId) {
    Scope scope = CURRENT.get();
    return scope != null && agentId != null && agentId.equals(scope.responderAgentId());
  }

  /** True when this agent leads the thread the current turn is in. */
  public static boolean leadsThread(UUID agentId) {
    Scope scope = CURRENT.get();
    return scope != null && scope.thread() && isResponder(agentId);
  }

  /** Something the user's screen should show with this turn's reply, such as a new thread. */
  public static void record(UiAction action) {
    Scope scope = CURRENT.get();
    if (scope != null) {
      synchronized (scope.actions()) {
        scope.actions().add(action);
      }
    }
  }

  /** What was recorded in this scope, in order. */
  public static List<UiAction> actions(Scope scope) {
    synchronized (scope.actions()) {
      return List.copyOf(scope.actions());
    }
  }
}
