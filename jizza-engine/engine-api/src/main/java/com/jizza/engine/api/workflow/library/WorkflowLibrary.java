package com.jizza.engine.api.workflow.library;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.dto.RunDto;
import com.jizza.engine.api.enums.WorkflowStatus;
import java.util.List;
import java.util.UUID;

/**
 * The user's saved workflows, as something Jizza can look a request up in (R31 reuse).
 *
 * <p>The point is that a job the user already has a workflow for runs that workflow, with its
 * fixed steps, rather than Jizza working it out again: in a chat, and in a scheduled task. Every
 * reuse goes through {@link #reuse}, which enforces the workflow's guardrails, so no caller can
 * start a library run the user did not allow. A workflow the user built without saving it for
 * reuse is still theirs to run from a chat on request, always asking first; never on a schedule.
 */
public interface WorkflowLibrary {

  /** Where a reuse comes from, which decides the guardrails that apply. */
  enum Use {
    /** The user asked in a conversation; Jizza runs it with their knowledge. */
    CHAT,
    /** A scheduled task is due and nobody is watching. */
    SCHEDULE
  }

  /**
   * The saved workflows that could do {@code request}, best first. For {@link Use#SCHEDULE} only
   * workflows the user allowed to run unattended are returned.
   */
  List<Match> find(UUID userId, String request, Use use, int limit);

  /**
   * Every workflow the user could run from a chat that shares anything with {@code request}, best
   * first, however loosely: for Jizza to judge when nothing fits well enough for {@link #find}.
   */
  default List<Match> search(UUID userId, String request, int limit) {
    return find(userId, request, Use.CHAT, limit);
  }

  /**
   * Every workflow the user could run from a chat, newest first: the ones saved for reuse with
   * their profile, and the others with the strictest one (ask before every run, never scheduled).
   */
  List<Match> list(UUID userId);

  /**
   * Starts a run of a library workflow, after checking its guardrails for this use.
   *
   * @param confirmed the user agreed to this run in this conversation; required when the
   *                  workflow's guardrails say to ask first
   * @param source    who is reusing it, recorded as the run's trigger ({@code chat:<conversation>},
   *                  {@code scheduled_task:<task>})
   * @throws com.jizza.engine.api.error.ValidationException when a guardrail stops the run
   */
  RunDto reuse(UUID userId, UUID workflowId, JsonNode input, Use use, String source,
               boolean confirmed);

  /**
   * Saves what the library keeps about a workflow, as the user set it. Turning scheduled runs on
   * pins that permission to the workflow's current steps. A null profile takes it out of the
   * library.
   */
  Match describe(UUID userId, UUID workflowId, ReuseProfile profile);

  /**
   * @param score how well the request fits, 0..1; only meaningful relative to other matches
   * @param why   the words the request and the workflow share, for Jizza and the user
   */
  record Match(UUID workflowId, String name, String description, WorkflowStatus status,
               int version, ReuseProfile profile, double score, List<String> why) {}
}
