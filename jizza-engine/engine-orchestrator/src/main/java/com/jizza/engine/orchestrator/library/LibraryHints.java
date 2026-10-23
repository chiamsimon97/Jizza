package com.jizza.engine.orchestrator.library;

import com.jizza.engine.api.workflow.library.WorkflowLibrary;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Tells Jizza, for this turn only, which saved workflows look like what the user just asked for.
 *
 * <p>The lookup is done for Jizza rather than left to it remembering to call
 * {@code find_saved_workflows}, so a request the user already has a workflow for reliably meets
 * that workflow. What Jizza does with it is still its call: the note says the candidates may fit,
 * and running one goes through {@code run_saved_workflow} and its guardrails.
 */
@Component
public class LibraryHints {

  private static final Logger log = LoggerFactory.getLogger(LibraryHints.class);

  static final int MAX = 3;

  private final ObjectProvider<WorkflowLibrary> library;

  public LibraryHints(ObjectProvider<WorkflowLibrary> library) {
    this.library = library;
  }

  /** {@code message} with a note of the saved workflows that may fit it, or unchanged when none do. */
  public String annotate(UUID userId, String message) {
    WorkflowLibrary lib = library.getIfAvailable();
    if (lib == null || message == null || message.isBlank()) return message;
    List<WorkflowLibrary.Match> matches;
    try {
      matches = lib.find(userId, message, WorkflowLibrary.Use.CHAT, MAX);
    } catch (RuntimeException e) {
      // A lookup that fails must never cost the user their reply.
      log.info("Could not look up saved workflows: {}", e.getMessage());
      return message;
    }
    if (matches.isEmpty()) return message;
    StringBuilder note = new StringBuilder("\n\n[Saved workflows that may already do this:");
    for (WorkflowLibrary.Match m : matches) {
      note.append("\n- \"").append(m.name()).append("\" (workflow_id ").append(m.workflowId())
          .append(")");
      if (m.profile() != null && m.profile().summary() != null) {
        note.append(": ").append(m.profile().summary());
      }
    }
    note.append("\nIf one of them does what the user is asking, use run_saved_workflow instead of "
        + "building or working out a new one, and tell the user you are using it. Check its "
        + "inputs and rules with find_saved_workflows first. If none really fits, ignore this.]");
    return message + note;
  }
}
