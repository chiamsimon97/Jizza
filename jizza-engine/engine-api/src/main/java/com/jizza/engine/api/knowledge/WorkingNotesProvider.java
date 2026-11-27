package com.jizza.engine.api.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;

/**
 * What an agent knows about doing its job, as opposed to what it knows about the user: the titles
 * of its own notes (so it knows what {@code knowledge_get} can open without guessing search words)
 * and the lessons the user approved that matter for this request.
 *
 * <p>Lessons are disclosed when they apply, not all at once: at the start of a turn only those the
 * request is about (or general ones), and when a tool call fails, the lessons about that tool or
 * that site, appended to the error the agent reads.
 */
public interface WorkingNotesProvider {

  /**
   * @param rendered  the block to place in the agent's prompt; empty when there is nothing
   * @param lessonIds the lessons included, so their use is stamped in their passports
   * @param noteCount how many note titles were listed
   */
  record WorkingNotes(String rendered, List<UUID> lessonIds, int noteCount) {

    public static final WorkingNotes EMPTY = new WorkingNotes("", List.of(), 0);

    public boolean isEmpty() { return rendered == null || rendered.isBlank(); }
  }

  WorkingNotes contextFor(UUID userId, UUID agentId, String request);

  /**
   * Lessons about what just failed, to append to the error the agent reads; empty when none apply.
   * Never throws.
   */
  String lessonsForFailure(UUID agentId, String toolName, JsonNode args, String error);
}
