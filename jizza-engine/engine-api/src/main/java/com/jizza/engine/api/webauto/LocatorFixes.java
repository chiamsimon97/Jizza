package com.jizza.engine.api.webauto;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.authz.Access;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Suggested fixes for web steps whose element a page change made impossible to find. The browser
 * offers candidates it found by the element's fingerprint; the server keeps them as one open fix
 * per workflow step and the person decides. Nothing here guesses a selector, and nothing changes a
 * workflow until the person applies a fix.
 */
public interface LocatorFixes {

  /**
   * A web step failed with {@code element_not_found} and candidates. Opens a fix for the step, or
   * refreshes the open one, and tells the person. Never applies anything.
   */
  void propose(FailedStep failed);

  /** The person's fixes, newest first; {@code status} may be null for all. */
  List<Fix> list(Access access, String status);

  /**
   * Saves a new version of the workflow where the step's element is the chosen candidate, with
   * the strategies it had before kept as fallbacks.
   */
  Applied apply(Access access, UUID fixId, int candidateIndex);

  void dismiss(Access access, UUID fixId);

  /**
   * @param error the {@code output.error} the browser sent: {@code kind}, {@code detail},
   *              {@code candidates}, {@code url}
   */
  record FailedStep(UUID userId, UUID workflowId, int workflowVersion, UUID runId, String nodeId,
                    JsonNode error) {}

  /** {@code candidates} are {@code [{element, score, text}]}, best first. */
  record Fix(UUID id, UUID workflowId, String workflowName, String nodeId, String nodeLabel,
             JsonNode candidates, String status, UUID runId, Instant createdAt) {}

  record Applied(UUID workflowId, int version) {}
}
