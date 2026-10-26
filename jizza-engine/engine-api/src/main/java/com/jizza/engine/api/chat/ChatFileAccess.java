package com.jizza.engine.api.chat;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Whose attached files the model may read on this thread: the user whose chat turn is being
 * answered. Set only around the reply to a message, so the router, background jobs and the
 * specialists Jizza delegates to (on other threads) never send files.
 */
public final class ChatFileAccess {

  private static final ThreadLocal<UUID> READER = new ThreadLocal<>();

  private ChatFileAccess() {}

  public static Optional<UUID> reader() {
    return Optional.ofNullable(READER.get());
  }

  public static <T> T run(UUID userId, Supplier<T> work) {
    UUID previous = READER.get();
    READER.set(userId);
    try {
      return work.get();
    } finally {
      if (previous == null) READER.remove();
      else READER.set(previous);
    }
  }
}
