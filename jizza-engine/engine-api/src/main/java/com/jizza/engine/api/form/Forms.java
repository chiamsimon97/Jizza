package com.jizza.engine.api.form;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Forms: templates built from fields, published at an unguessable public link, whose submissions
 * start the workflows the person chose. Fields made from a workflow's steps fill those steps'
 * settings in the runs they start.
 */
public interface Forms {

  /** A workflow a form starts, and whether it is on, so the builder can say why one would not run. */
  record LinkedWorkflow(UUID id, String name, boolean enabled) {}

  record FormDto(UUID id, String name, String description, JsonNode fields, JsonNode settings,
                 String status, String publicToken, Instant publishedAt, UUID sourceWorkflowId,
                 int submissionCount, Instant createdAt, Instant updatedAt,
                 List<LinkedWorkflow> workflows) {}

  /** What the builder saves. */
  record FormDraft(String name, String description, JsonNode fields, JsonNode settings,
                   UUID sourceWorkflowId) {}

  /** What a stranger with the link sees: no owner, no bindings, no pending fields. */
  record PublicForm(String name, String description, JsonNode fields, JsonNode settings) {}

  record Submission(UUID id, int number, JsonNode data, JsonNode runs, Instant submittedAt) {}

  record SubmitResult(int number, String message) {}

  List<FormDto> list(UUID userId);

  FormDto get(UUID userId, UUID formId);

  FormDto create(UUID userId, FormDraft draft);

  FormDto update(UUID userId, UUID formId, FormDraft draft);

  void delete(UUID userId, UUID formId);

  FormDto publish(UUID userId, UUID formId);

  FormDto unpublish(UUID userId, UUID formId);

  /** Chooses the workflows the form starts; each keeps at most one form. */
  FormDto setWorkflows(UUID userId, UUID formId, List<UUID> workflowIds);

  /** The answers, with each file as its name, type and size; {@link #file} gives a file itself. */
  List<Submission> submissions(UUID userId, UUID formId, int limit);

  record FileContent(String name, String type, byte[] bytes) {}

  FileContent file(UUID userId, UUID formId, UUID submissionId, String field);

  PublicForm publicForm(String token);

  /**
   * Checks and keeps a submission, then starts the form's workflows that are on.
   *
   * @param client who is submitting, for the rate limit (an address); may be null
   */
  SubmitResult submit(String token, JsonNode data, String client);
}
