package com.jizza.engine.api.webauto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.authz.Access;
import com.jizza.engine.api.workflow.library.ReuseProfile;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Recording what a person does in their browser or in Windows apps and turning it into a workflow module. The
 * captured steps live here only while the person records and reviews them; saving makes an
 * ordinary workflow, so there is no separate replay engine.
 */
public interface Recordings {

  Recording start(Access access, UUID deviceId, String url, String name);

  /**
   * Edit recording: opens a saved browser recording (its workflow) in {@code deviceId} to change
   * it there, step by step. The steps are the workflow's, read back from its graph; {@code
   * testValues} fill its inputs for the test runs made while editing. Saving it writes a new
   * version of the same workflow (or a new one, with {@link SaveRequest#asNew()}).
   */
  default Recording openForEdit(Access access, UUID workflowId, UUID deviceId, JsonNode testValues) {
    throw new UnsupportedOperationException("Editing recordings is not supported here");
  }

  /** The workflows that run {@code workflowId} as a step (Run a workflow, For each). */
  default List<Linked> linked(Access access, UUID workflowId) {
    return List.of();
  }

  /**
   * Brings the steps that run {@code workflowId} in each of {@code targets} up to date with its
   * inputs now: inputs it no longer has are taken out, and new ones are added with their default.
   */
  default List<LinkedUpdate> updateLinked(Access access, UUID workflowId, List<UUID> targets) {
    return List.of();
  }

  /** The person's recordings, newest first; {@code statuses} may be empty for all. */
  List<Recording> list(Access access, Collection<String> statuses);

  Recording get(Access access, UUID id);

  Recording edit(Access access, UUID id, List<EditOp> ops);

  Recording stop(Access access, UUID id);

  void discard(Access access, UUID id);

  /**
   * The reviewed steps as nodes and edges to place in a workflow being built, with no module
   * around them. The recording is finished once it has been handed over.
   */
  JsonNode insertable(Access access, UUID id);

  SaveResult save(Access access, UUID id, SaveRequest request);

  /**
   * {@code steps} is null in lists. {@code kind} is {@code web} (a browser, from {@code startUrl})
   * or {@code desktop} (Windows apps, started with a hotkey on the PC; {@code app} is the app it
   * started in, and there is no start URL).
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  record Recording(UUID id, String name, String status, UUID deviceId, String deviceName,
                   String startUrl, int stepCount, UUID workflowId, Instant updatedAt,
                   List<RecordedStep> steps, String kind, String app, UUID sourceWorkflowId,
                   Integer sourceVersion) {

    public Recording(UUID id, String name, String status, UUID deviceId, String deviceName,
                     String startUrl, int stepCount, UUID workflowId, Instant updatedAt,
                     List<RecordedStep> steps, String kind, String app) {
      this(id, name, status, deviceId, deviceName, startUrl, stepCount, workflowId, updatedAt,
          steps, kind, app, null, null);
    }

    public Recording(UUID id, String name, String status, UUID deviceId, String deviceName,
                     String startUrl, int stepCount, UUID workflowId, Instant updatedAt,
                     List<RecordedStep> steps) {
      this(id, name, status, deviceId, deviceName, startUrl, stepCount, workflowId, updatedAt,
          steps, "web", null);
    }
  }

  record RecordedStep(int seq, int position, JsonNode step, boolean deleted) {}

  /**
   * One review edit: {@code delete}, {@code move}, {@code relabel}, {@code map}, {@code secret},
   * {@code insertCheck} or {@code update} (a {@code patch} of {@code disabled}, {@code optional},
   * {@code timeoutMs}, {@code when}, {@code element} or a {@code value} of one field).
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record EditOp(String op, int seq, Integer toIndex, String label, String field, Input input,
                String secretRef, Check check, JsonNode patch) {

    public EditOp(String op, int seq, Integer toIndex, String label, String field, Input input,
                  String secretRef, Check check) {
      this(op, seq, toIndex, label, field, input, secretRef, check, null);
    }
  }

  /**
   * A typed value the person turns into a module input. A date or date and time keeps the
   * format the site writes it in (DD/MM/YYYY), so the value given is written that way. A
   * {@code required} (mandatory) one keeps no recorded value: each run must be given it, and
   * the step's card waits for it to be filled in before it can be submitted.
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record Input(String name, String type, @JsonProperty("default") JsonNode defaultValue,
               String format, Boolean required) {

    public Input(String name, String type, JsonNode defaultValue) {
      this(name, type, defaultValue, null, null);
    }

    public Input(String name, String type, JsonNode defaultValue, String format) {
      this(name, type, defaultValue, format, null);
    }

    public boolean mandatory() {
      return Boolean.TRUE.equals(required);
    }
  }

  /** A check step built from an earlier step's element. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record Check(String action, int afterSeq) {}

  /** {@code asNew}: an edited recording is saved as a new recording instead of a new version. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record SaveRequest(String name, String description, ReuseProfile reuse, Boolean asNew) {

    public SaveRequest(String name, String description, ReuseProfile reuse) {
      this(name, description, reuse, null);
    }
  }

  /**
   * {@code updated}: an existing recording got a new version (Edit recording); {@code linked}: the
   * workflows that run it as a step, to offer updating.
   */
  record SaveResult(UUID workflowId, int version, boolean updated, List<Linked> linked) {

    public SaveResult(UUID workflowId, int version) {
      this(workflowId, version, false, List.of());
    }
  }

  /**
   * A workflow with steps that run a recording. {@code missingInputs}: inputs the recording needs
   * that those steps do not give; {@code unknownInputs}: values they give for inputs it no longer
   * has. {@code canUpdate} is false for a workflow past draft, whose changes go through review.
   */
  record Linked(UUID workflowId, String name, String status, List<String> stepLabels,
                List<String> missingInputs, List<String> unknownInputs, boolean canUpdate,
                String note) {}

  record LinkedUpdate(UUID workflowId, String name, boolean updated, Integer version, String note) {}
}
