package com.jizza.engine.api.chat;

import com.jizza.engine.api.orchestrator.AssistantReply;
import java.util.List;
import java.util.UUID;

/**
 * Chat turns as jobs (R18/R43/R53). A turn starts, runs in the background, and reports everything
 * it does as events on the user's socket; the reply arrives as the job's last event.
 */
public interface ChatService {

  /** Starts a turn and returns at once. */
  TurnRef submit(UUID userId, TurnSubmission submission);

  /**
   * Starts a turn and waits for its reply, for callers that want request/response. Errors the turn
   * hit (budget, a disabled agent) are thrown as they would have been synchronously.
   */
  AssistantReply submitAndWait(UUID userId, TurnSubmission submission);

  /** The job with its events after {@code afterSeq}, for a client catching up after a reconnect. */
  JobDto job(UUID userId, UUID jobId, int afterSeq);

  /** The conversation's jobs, newest last, without events. */
  List<JobDto> jobs(UUID userId, UUID conversationId);

  /** Asks a running job to stop. Stopping a finished job does nothing. */
  void cancel(UUID userId, UUID jobId);
}
