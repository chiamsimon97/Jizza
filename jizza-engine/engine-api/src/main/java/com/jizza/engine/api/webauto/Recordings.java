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
                   List<RecordedStep> steps, String kind, String app) {

    public Recording(UUID id, String name, String status, UUID deviceId, String deviceName,
                     String startUrl, int stepCount, UUID workflowId, Instant updatedAt,
                     List<RecordedStep> steps) {
      this(id, name, status, deviceId, deviceName, startUrl, stepCount, workflowId, updatedAt,
          steps, "web", null);
    }
  }

  record RecordedStep(int seq, int position, JsonNode step, boolean deleted) {}

  /**
   * One review edit: {@code delete}, {@code move}, {@code relabel}, {@code map}, {@code secret} or
   * {@code insertCheck}.
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record EditOp(String op, int seq, Integer toIndex, String label, String field, Input input,
                String secretRef, Check check) {}

  /** A typed value the person turns into a module input. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record Input(String name, String type, @JsonProperty("default") JsonNode defaultValue) {}

  /** A check step built from an earlier step's element. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record Check(String action, int afterSeq) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record SaveRequest(String name, String description, ReuseProfile reuse) {}

  record SaveResult(UUID workflowId, int version) {}
}
