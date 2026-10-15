package com.jizza.engine.api.memory;

import java.util.List;
import java.util.UUID;

/**
 * Keeps durable facts found in what a job fetched: an account balance a broker returned, a policy
 * renewal date from an insurer's API. Everything here came from outside the user, so it is stored
 * unconfirmed (source {@code tool}) and can never become an instruction; the user confirms or
 * forgets each fact from the Memory panel.
 */
public interface OutputLearner {

  void afterJob(JobOutput output);

  /** What a finished job found. {@code findings} are already summaries, never raw payloads. */
  record JobOutput(UUID userId, UUID conversationId, UUID jobId, String userText,
                   List<Finding> findings) {}

  /**
   * One piece of output. {@code service} names the connected service it came from when known
   * (Zerodha, HDFC); {@code agentName} is the specialist that reported it.
   */
  record Finding(UUID agentId, String agentName, String service, String text) {}
}
