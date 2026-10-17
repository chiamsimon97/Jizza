package com.jizza.engine.chat.turn;

import com.jizza.engine.persistence.repository.ChatJobRepository;
import com.jizza.engine.persistence.repository.MessageRepository;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns run in this process, so a turn still marked running at startup was cut off by the last
 * shutdown. Marking it failed frees its conversation for the next message and tells the user what
 * happened instead of leaving a reply that never finishes.
 */
@Component
class AbandonedTurns {

  private static final Logger log = LoggerFactory.getLogger(AbandonedTurns.class);

  private final ChatJobRepository jobs;
  private final MessageRepository messages;

  AbandonedTurns(ChatJobRepository jobs, MessageRepository messages) {
    this.jobs = jobs;
    this.messages = messages;
  }

  @EventListener(ApplicationReadyEvent.class)
  @Transactional
  public void failAbandoned() {
    int failed = jobs.failAbandoned(Instant.now());
    messages.failAbandoned();
    if (failed > 0) log.info("Marked {} interrupted chat turn(s) as failed", failed);
  }
}
