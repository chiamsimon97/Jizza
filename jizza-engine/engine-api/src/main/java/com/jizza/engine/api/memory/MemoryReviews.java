package com.jizza.engine.api.memory;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The memory review: learning that happens after the work. Once a night (or when the user asks) it
 * reads the user's recent chats, the tool calls that failed in their jobs and the workflow steps
 * that failed in their runs, and suggests changes: merge facts that say the same thing, confirm a
 * fact the user keeps repeating, retire one they contradicted, reword one, or teach an agent a
 * lesson about a tool, site or workflow that keeps going wrong.
 *
 * <p>It only suggests. Nothing in memory changes until the user approves a suggestion, and every
 * approved change goes through the memory and knowledge stores like any other edit, so history,
 * the audit log and the Memory page see it the usual way.
 */
public interface MemoryReviews {

  /** Runs a review now over the activity since the last one; refused while one ran very recently. */
  Review runNow(UUID userId);

  /** The suggestions with these statuses ({@code open} when empty), newest first. */
  List<Proposal> proposals(UUID userId, List<String> statuses, int limit);

  /**
   * Applies a suggestion. {@code content}, when given, replaces the wording the review suggested
   * (the user edited it first). A suggestion whose facts changed since it was made is refused and
   * marked {@code stale}.
   */
  Proposal approve(UUID userId, UUID proposalId, String content);

  /** The user says no; the same suggestion is not raised again for a while. */
  Proposal dismiss(UUID userId, UUID proposalId);

  Status status(UUID userId);

  /**
   * @param status    {@code running}, {@code done}, {@code skipped} or {@code failed}
   * @param detail    why it was skipped or failed, in plain words
   * @param proposals how many suggestions it made
   */
  record Review(UUID id, String trigger, String status, String detail, int proposals,
                Instant windowStart, Instant windowEnd, Instant startedAt, Instant finishedAt) {}

  /**
   * @param kind        {@code merge}, {@code confirm}, {@code retire}, {@code update} or
   *                    {@code lesson}
   * @param summary     what would change, in one line
   * @param content     the wording the change would save (a merged fact, a reworded fact, a lesson)
   * @param facts       the facts it touches, with what they say now
   * @param agent       for a lesson: the agent that would learn it
   * @param about       for a lesson: what it is about
   * @param evidence    what it is based on: {@code kind} (chat, tool, step), a label, a link and when
   * @param occurrences how many times it happened in the evidence
   */
  record Proposal(UUID id, String kind, String status, String summary, String reason,
                  String content, List<FactRef> facts, AgentRef agent, String about,
                  List<Evidence> evidence, int occurrences, Instant createdAt, Instant decidedAt,
                  String resultRef) {}

  record FactRef(UUID id, String content, String status) {}

  record AgentRef(UUID id, String key, String name) {}

  /**
   * @param kind {@code chat} (something the user said), {@code tool} (a failed tool call) or
   *             {@code step} (a failed workflow step)
   * @param link where to see it in the app, or null
   */
  record Evidence(String kind, String label, String detail, String link, Instant at, JsonNode ids) {}

  /**
   * @param enabled     whether the nightly review runs for this user
   * @param last        the latest review, or null when there has not been one
   * @param open        how many suggestions are waiting
   * @param nextRunAt   when the next nightly review is due, or null when it is off
   */
  record Status(boolean enabled, Review last, long open, Instant nextRunAt) {}
}
