package com.jizza.engine.api.queue;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

/** Postgres-backed durable job queue driving the scheduler and workflow workers (R42). */
public interface JobQueue {

  UUID enqueue(UUID userId, String type, JsonNode payload, Instant runAfter);

  UUID enqueue(UUID userId, String type, JsonNode payload);

  /** Register a handler for a job type. Handlers must be idempotent. */
  void register(String type, JobHandler handler);

  interface JobHandler {
    void handle(Job job);
  }

  record Job(UUID id, UUID userId, String type, JsonNode payload, int attempts) {}
}
