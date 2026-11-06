package com.jizza.engine.webauto;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps recordings tidy: ends the ones whose browser went away, and removes the captured steps of
 * discarded recordings and of reviews nobody came back to for a week. Safe to run on every node
 * because each step is idempotent.
 */
@Component
class RecordingSweeper {

  private static final Logger log = LoggerFactory.getLogger(RecordingSweeper.class);

  private final RecordingService recordings;

  RecordingSweeper(RecordingService recordings) {
    this.recordings = recordings;
  }

  @Scheduled(fixedDelayString = "${jizza.webauto.sweep-interval:PT1M}")
  void sweep() {
    try {
      recordings.sweep(Instant.now());
    } catch (RuntimeException e) {
      log.warn("Recording sweep failed", e);
    }
  }
}
